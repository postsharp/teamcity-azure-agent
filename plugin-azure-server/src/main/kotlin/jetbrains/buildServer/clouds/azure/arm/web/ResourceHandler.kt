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

package jetbrains.buildServer.clouds.azure.arm.web

import jetbrains.buildServer.clouds.azure.arm.connector.AzureApiConnector
import jetbrains.buildServer.controllers.BasePropertiesBean
import org.jdom.Content
import javax.servlet.http.HttpServletRequest

/**
 * Request handler.
 */
internal interface ResourceHandler {
    suspend fun handle(request: HttpServletRequest, context: ResourceHandlerContext): Content
}

internal data class ResourceHandlerContext(val apiConnector: AzureApiConnector, val propertiesBean: BasePropertiesBean)
