/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package jetbrains.buildServer.clouds.azure.arm.throttler

import com.intellij.openapi.diagnostic.Logger
import com.microsoft.azure.credentials.AzureTokenCredentials
import com.microsoft.azure.management.Azure
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.version.ServerVersionHolder
import rx.Single
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class AzureThrottlerAdapterImpl (
        azureConfigurable: AzureConfigurableWithNetworkInterceptors,
        credentials: AzureTokenCredentials,
        subscriptionId: String?,
        override val name: String
) : AzureThrottlerAdapter<Azure> {
    @Suppress("JoinDeclarationAndAssignment")
    private var myInterceptor: AzureThrottlerInterceptor

    private val myAzure: Azure

    private val myRemainingReads = AtomicLong(DEFAULT_REMAINING_READS_PER_HOUR)
    private val myWindowStartTime = AtomicReference<LocalDateTime>(LocalDateTime.now(Clock.systemUTC()))
    private val myDefaultReads = AtomicLong(DEFAULT_REMAINING_READS_PER_HOUR)

    init {
        myInterceptor = AzureThrottlerInterceptor(this, name)

        myAzure = azureConfigurable
                .configureProxy()
                .withNetworkInterceptor(myInterceptor)
                .withUserAgent("TeamCity Server ${ServerVersionHolder.getVersion().displayVersion}")
                .authenticate(credentials)
                .withSubscription(subscriptionId)

        myAzure
                .deployments()
                .manager()
                .inner()
                .azureClient
                .setLongRunningOperationRetryTimeout(TeamCityProperties.getInteger(TEAMCITY_CLOUDS_AZURE_DEPLOYMENT_LONG_RUNNING_QUERY_RETRY_TIMEOUT, 30))
    }
    override val api: Azure
        get() = myAzure


    override fun getDefaultReads(): Long {
        return myDefaultReads.get()
    }

    override fun setThrottlerTime(milliseconds: Long) {
        myInterceptor.setThrottlerTime(milliseconds)
    }

    override fun getThrottlerTime(): Long {
        return myInterceptor.getThrottlerTime()
    }

    override fun getWindowWidthInMilliseconds(): Long {
        return max(0,
                myWindowStartTime.get().plusHours(1).toInstant(ZoneOffset.UTC).toEpochMilli() -
                        LocalDateTime.now(Clock.systemUTC()).toInstant(ZoneOffset.UTC).toEpochMilli())
    }

    override fun getWindowStartDateTime(): LocalDateTime {
        return myWindowStartTime.get()
    }

    override fun getRemainingReads(): Long {
        return myRemainingReads.get()
    }

    override fun <T> execute(queryFactory: (Azure) -> Single<T>): Single<AzureThrottlerAdapterResult<T>> {
        return queryFactory(myAzure)
                .doOnSubscribe { myInterceptor.onBeginRequestsSequence() }
                .doOnUnsubscribe { myInterceptor.onEndRequestsSequence() }
                .map {
                    AzureThrottlerAdapterResult(
                            it,
                            myInterceptor.getRequestsSequenceLength(),
                            false)
                }
    }

    override fun notifyRemainingReads(value: Long?, requestCount: Long) {
        if (value == null) {
            myRemainingReads.getAndUpdate { max(MIN_REMAINING_READS, it - requestCount) }
        } else {
            if (myRemainingReads.get() < value) {
                myWindowStartTime.set(LocalDateTime.now(Clock.systemUTC()))
            }
            myRemainingReads.set(max(MIN_REMAINING_READS, value))
            myDefaultReads.getAndUpdate { max(it, myRemainingReads.get()) }
        }
    }

    override fun logDiagnosticInfo() {
        LOG.debug("[${name}] info: " +
                "Default reads: ${getDefaultReads()}, " +
                "Remaining reads: ${getRemainingReads()}, " +
                "Window start time: ${getWindowStartDateTime()}, " +
                "Window width: ${Duration.ofMillis(getWindowWidthInMilliseconds())}, " +
                "Throttler time: ${Duration.ofMillis(getThrottlerTime())}")
    }

    companion object {
        private val LOG = Logger.getInstance(AzureThrottlerAdapterImpl::class.java.name)
        private val MIN_REMAINING_READS = 1L
    }
}
