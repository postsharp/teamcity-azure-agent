/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.clouds.azure.arm

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.clouds.CloudException
import jetbrains.buildServer.clouds.CloudInstanceUserData
import jetbrains.buildServer.clouds.InstanceStatus
import jetbrains.buildServer.clouds.QuotaException
import jetbrains.buildServer.clouds.azure.AzureUtils
import jetbrains.buildServer.clouds.azure.arm.connector.AzureApiConnector
import jetbrains.buildServer.clouds.azure.arm.types.*
import jetbrains.buildServer.clouds.base.AbstractCloudImage
import jetbrains.buildServer.clouds.base.connector.AbstractInstance
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo
import jetbrains.buildServer.serverSide.TeamCityProperties
import kotlinx.coroutines.*
import java.lang.StringBuilder
import java.util.*

/**
 * Azure cloud image.
 */
class AzureCloudImage constructor(private val myImageDetails: AzureCloudImageDetails,
                                  private val myApiConnector: AzureApiConnector)
    : AbstractCloudImage<AzureCloudInstance, AzureCloudImageDetails>(myImageDetails.sourceId, myImageDetails.sourceId) {

    private val myImageHandlers = mapOf(
            AzureCloudImageType.Vhd to AzureVhdHandler(myApiConnector),
            AzureCloudImageType.Image to AzureImageHandler(myApiConnector),
            AzureCloudImageType.Template to AzureTemplateHandler(myApiConnector),
            AzureCloudImageType.Container to AzureContainerHandler(myApiConnector)
    )
    private val myInstanceHandler = AzureInstanceHandler(myApiConnector)

    private val myActiveStatuses = setOf(
            InstanceStatus.SCHEDULED_TO_START,
            InstanceStatus.STARTING,
            InstanceStatus.RUNNING,
            InstanceStatus.RESTARTING,
            InstanceStatus.SCHEDULED_TO_STOP,
            InstanceStatus.STOPPING,
            InstanceStatus.ERROR
    )

    private var azureCpuQuotaExceeded: Set<String>? = null

    override fun getImageDetails(): AzureCloudImageDetails = myImageDetails

    override fun createInstanceFromReal(realInstance: AbstractInstance): AzureCloudInstance {
        return AzureCloudInstance(this, realInstance.name).apply {
            properties = realInstance.properties
        }
    }

    override fun detectNewInstances(realInstances: MutableMap<String, out AbstractInstance>?) {
        super.detectNewInstances(realInstances)
        if (realInstances == null) {
            return
        }

        // Update properties
        instances.forEach { instance ->
            realInstances[instance.instanceId]?.let {
                instance.properties = it.properties
            }
        }
    }

    override fun canStartNewInstance(): Boolean {
        if (activeInstances.size >= myImageDetails.maxInstances) return false
        // Check Azure CPU quota state
        azureCpuQuotaExceeded?.let { instances ->
            if (instances == getInstanceIds()) {
                return false
            } else {
                azureCpuQuotaExceeded = null
                LOG.info("Azure CPU quota limit has been reset due to change in the number of active instances for image ${imageDetails.sourceId}.")
            }
        }
        if (imageDetails.deployTarget == AzureCloudDeployTarget.Instance && stoppedInstances.isEmpty()) {
            return false
        }
        return true
    }

    override fun startNewInstance(userData: CloudInstanceUserData) = runBlocking {
        if (!canStartNewInstance()) {
            throw QuotaException("Unable to start more instances. Limit has reached")
        }

        val instance = if (myImageDetails.deployTarget == AzureCloudDeployTarget.Instance) {
            startStoppedInstance()
        } else {
            tryToStartStoppedInstance(userData) ?: createInstance(userData)
        }
        instance.apply {
            setStartDate(Date())
        }
    }

    /**
     * Creates a new virtual machine.
     *
     * @param userData info about server.
     * @return created instance.
     */
    private fun createInstance(userData: CloudInstanceUserData): AzureCloudInstance {
        val name = getInstanceName()
        val instance = AzureCloudInstance(this, name)
        instance.status = InstanceStatus.SCHEDULED_TO_START
        val data = AzureUtils.setVmNameForTag(userData, name)

        GlobalScope.launch {
            val hash = handler!!.getImageHash(imageDetails)

            instance.properties[AzureConstants.TAG_PROFILE] = userData.profileId
            instance.properties[AzureConstants.TAG_SOURCE] = imageDetails.sourceId
            instance.properties[AzureConstants.TAG_DATA_HASH] = getDataHash(data)
            instance.properties[AzureConstants.TAG_IMAGE_HASH] = hash

            try {
                LOG.info("Creating new virtual machine ${instance.name}")
                myApiConnector.createInstance(instance, data)
                instance.status = InstanceStatus.RUNNING
            } catch (e: Throwable) {
                LOG.warnAndDebugDetails(e.message, e)
                handleDeploymentError(e)

                instance.status = InstanceStatus.ERROR
                instance.updateErrors(TypedCloudErrorInfo.fromException(e))

                if (TeamCityProperties.getBooleanOrTrue(AzureConstants.PROP_DEPLOYMENT_DELETE_FAILED)) {
                    LOG.info("Removing allocated resources for virtual machine ${instance.name}")
                    try {
                        myApiConnector.deleteInstance(instance)
                        LOG.info("Allocated resources for virtual machine ${instance.name} have been removed")
                        removeInstance(instance.instanceId)
                    } catch (e: Throwable) {
                        val message = "Failed to delete allocated resources for virtual machine ${instance.name}: ${e.message}"
                        LOG.warnAndDebugDetails(message, e)
                    }
                } else {
                    LOG.info("Allocated resources for virtual machine ${instance.name} would not be deleted. Cleanup them manually.")
                }
            }
        }

        addInstance(instance)

        return instance
    }

    /**
     * Tries to find and start stopped instance.
     *
     * @return instance if it found.
     */
    private suspend fun tryToStartStoppedInstance(userData: CloudInstanceUserData) = coroutineScope {
        val instances = stoppedInstances
        if (instances.isNotEmpty()) {
            val validInstances = if (myImageDetails.behaviour.isDeleteAfterStop) {
                LOG.info("Will remove all virtual machines due to cloud image settings")
                emptyList()
            } else {
                instances.filter {
                    if (!isSameImageInstance(it)) {
                        LOG.info("Will remove virtual machine ${it.name} due to changes in image source")
                        return@filter false
                    }
                    val data = AzureUtils.setVmNameForTag(userData, it.name)
                    if (it.properties[AzureConstants.TAG_DATA_HASH] != getDataHash(data)) {
                        LOG.info("Will remove virtual machine ${it.name} due to changes in cloud profile")
                        return@filter false
                    }
                    return@filter true
                }
            }

            val invalidInstances = instances - validInstances
            val instance = validInstances.firstOrNull()

            instance?.status = InstanceStatus.SCHEDULED_TO_START

            GlobalScope.launch {
                invalidInstances.forEach {
                    try {
                        LOG.info("Removing virtual machine ${it.name}")
                        myApiConnector.deleteInstance(it)
                        removeInstance(it.instanceId)
                    } catch (e: Throwable) {
                        LOG.warnAndDebugDetails(e.message, e)
                        it.status = InstanceStatus.ERROR
                        it.updateErrors(TypedCloudErrorInfo.fromException(e))
                    }
                }

                instance?.let {
                    try {
                        LOG.info("Starting stopped virtual machine ${it.name}")
                        myApiConnector.startInstance(it)
                        instance.status = InstanceStatus.RUNNING
                    } catch (e: Throwable) {
                        LOG.warnAndDebugDetails(e.message, e)
                        handleDeploymentError(e)

                        it.status = InstanceStatus.ERROR
                        it.updateErrors(TypedCloudErrorInfo.fromException(e))
                    }
                }
            }

            return@coroutineScope instance
        }

        null
    }

    /**
     * Starts stopped instance.
     *
     * @return instance.
     */
    private fun startStoppedInstance(): AzureCloudInstance {
        val instance = stoppedInstances.singleOrNull()
                ?: throw CloudException("Instance ${imageDetails.vmNamePrefix ?: imageDetails.sourceId} was not found")

        instance.status = InstanceStatus.SCHEDULED_TO_START

        GlobalScope.launch {
            try {
                LOG.info("Starting virtual machine ${instance.name}")
                myApiConnector.startInstance(instance)
                instance.status = InstanceStatus.RUNNING
            } catch (e: Throwable) {
                LOG.warnAndDebugDetails(e.message, e)
                handleDeploymentError(e)

                instance.status = InstanceStatus.ERROR
                instance.updateErrors(TypedCloudErrorInfo.fromException(e))
            }
        }

        return instance
    }

    private suspend fun isSameImageInstance(instance: AzureCloudInstance) = coroutineScope {
        if (imageDetails.deployTarget == AzureCloudDeployTarget.Instance) {
            return@coroutineScope true
        }
        handler?.let {
            val hash = it.getImageHash(imageDetails)
            return@coroutineScope hash == instance.properties[AzureConstants.TAG_IMAGE_HASH]
        }
        false
    }

    private fun getDataHash(userData: CloudInstanceUserData): String {
        val dataHash = StringBuilder(userData.agentName)
                .append(userData.profileId)
                .append(userData.serverAddress)
                .toString()
                .hashCode()
        return Integer.toHexString(dataHash)
    }

    override fun restartInstance(instance: AzureCloudInstance) {
        instance.status = InstanceStatus.RESTARTING

        GlobalScope.launch {
            try {
                LOG.info("Restarting virtual machine ${instance.name}")
                myApiConnector.restartInstance(instance)
                instance.status = InstanceStatus.RUNNING
            } catch (e: Throwable) {
                LOG.warnAndDebugDetails(e.message, e)
                instance.status = InstanceStatus.ERROR
                instance.updateErrors(TypedCloudErrorInfo.fromException(e))
            }
        }
    }

    override fun terminateInstance(instance: AzureCloudInstance) {
        if (instance.properties.containsKey(AzureConstants.TAG_INVESTIGATION)) {
            LOG.info("Could not stop virtual machine ${instance.name} under investigation. To do that remove ${AzureConstants.TAG_INVESTIGATION} tag from it.")
            return
        }

        instance.status = InstanceStatus.SCHEDULED_TO_STOP

        GlobalScope.launch {
            try {
                val sameVhdImage = isSameImageInstance(instance)
                if (myImageDetails.behaviour.isDeleteAfterStop) {
                    LOG.info("Removing virtual machine ${instance.name} due to cloud image settings")
                    myApiConnector.deleteInstance(instance)
                    removeInstance(instance.instanceId)
                } else if (!sameVhdImage) {
                    LOG.info("Removing virtual machine ${instance.name} due to cloud image retention policy")
                    myApiConnector.deleteInstance(instance)
                    removeInstance(instance.instanceId)
                } else {
                    LOG.info("Stopping virtual machine ${instance.name}")
                    myApiConnector.stopInstance(instance)
                    instance.status = InstanceStatus.STOPPED
                }

                LOG.info("Virtual machine ${instance.name} has been successfully terminated")
            } catch (e: Throwable) {
                LOG.warnAndDebugDetails(e.message, e)
                instance.status = InstanceStatus.ERROR
                instance.updateErrors(TypedCloudErrorInfo.fromException(e))
            }
        }
    }

    override fun getAgentPoolId(): Int? = myImageDetails.agentPoolId

    val handler: AzureHandler?
        get() = if (imageDetails.deployTarget == AzureCloudDeployTarget.Instance) {
            myInstanceHandler
        } else {
            myImageHandlers[imageDetails.type]
        }

    private fun getInstanceName(): String {
        val keys = instances.map { it.instanceId.toLowerCase() }
        val sourceName = myImageDetails.sourceId.toLowerCase()
        var i = 1

        while (keys.contains(sourceName + i)) i++

        return sourceName + i
    }

    /**
     * Returns active instances.
     *
     * @return instances.
     */
    private val activeInstances: List<AzureCloudInstance>
        get() = instances.filter { instance -> myActiveStatuses.contains(instance.status) }

    /**
     * Returns stopped instances.
     *
     * @return instances.
     */
    private val stoppedInstances: List<AzureCloudInstance>
        get() = instances.filter { instance ->
            instance.status == InstanceStatus.STOPPED &&
            !instance.properties.containsKey(AzureConstants.TAG_INVESTIGATION)
        }

    private fun handleDeploymentError(e: Throwable) {
        if (AZURE_CPU_QUOTA_EXCEEDED.containsMatchIn(e.message!!)) {
            azureCpuQuotaExceeded = getInstanceIds()
            LOG.info("Exceeded Azure CPU quota limit for image ${imageDetails.sourceId}. Would not start new cloud instances until active instances termination.")
        }
    }

    private fun getInstanceIds() = activeInstances.asSequence().map { it.instanceId }.toSortedSet()

    companion object {
        private val LOG = Logger.getInstance(AzureCloudImage::class.java.name)
        private val AZURE_CPU_QUOTA_EXCEEDED = Regex("Operation results in exceeding quota limits of Core\\. Maximum allowed: \\d+, Current in use: \\d+, Additional requested: \\d+\\.")
    }
}
