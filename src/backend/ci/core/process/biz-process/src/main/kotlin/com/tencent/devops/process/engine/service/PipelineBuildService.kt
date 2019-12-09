/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.engine.service

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.api.pojo.BuildHistoryPage
import com.tencent.devops.common.api.pojo.IdValue
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.pojo.SimpleResult
import com.tencent.devops.common.api.util.EnvUtils
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.pipeline.enums.ManualReviewAction
import com.tencent.devops.common.pipeline.enums.StartType
import com.tencent.devops.common.pipeline.pojo.BuildFormProperty
import com.tencent.devops.common.pipeline.pojo.BuildParameters
import com.tencent.devops.common.pipeline.pojo.element.agent.ManualReviewUserTaskElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.ManualTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.RemoteTriggerElement
import com.tencent.devops.common.pipeline.utils.SkipElementUtils
import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.HomeHostUtil
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.log.utils.LogUtils
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.engine.control.lock.BuildIdLock
import com.tencent.devops.process.engine.interceptor.InterceptData
import com.tencent.devops.process.engine.interceptor.PipelineInterceptorChain
import com.tencent.devops.process.engine.pojo.PipelineInfo
import com.tencent.devops.process.jmx.api.ProcessJmxApi
import com.tencent.devops.process.permission.PipelinePermissionService
import com.tencent.devops.process.pojo.BuildBasicInfo
import com.tencent.devops.process.pojo.BuildHistory
import com.tencent.devops.process.pojo.BuildHistoryVariables
import com.tencent.devops.process.pojo.BuildHistoryWithPipelineVersion
import com.tencent.devops.process.pojo.BuildHistoryWithVars
import com.tencent.devops.process.pojo.BuildManualStartupInfo
import com.tencent.devops.process.pojo.ReviewParam
import com.tencent.devops.process.pojo.VmInfo
import com.tencent.devops.process.pojo.mq.PipelineBuildContainerEvent
import com.tencent.devops.process.pojo.pipeline.ModelDetail
import com.tencent.devops.process.pojo.pipeline.PipelineLatestBuild
import com.tencent.devops.process.service.BuildStartupParamService
import com.tencent.devops.process.service.ParamService
import com.tencent.devops.process.utils.PIPELINE_NAME
import com.tencent.devops.process.utils.PIPELINE_RETRY_BUILD_ID
import com.tencent.devops.process.utils.PIPELINE_RETRY_COUNT
import com.tencent.devops.process.utils.PIPELINE_RETRY_START_TASK_ID
import com.tencent.devops.process.utils.PIPELINE_START_CHANNEL
import com.tencent.devops.process.utils.PIPELINE_START_MOBILE
import com.tencent.devops.process.utils.PIPELINE_START_PARENT_BUILD_ID
import com.tencent.devops.process.utils.PIPELINE_START_PARENT_BUILD_TASK_ID
import com.tencent.devops.process.utils.PIPELINE_START_PARENT_PIPELINE_ID
import com.tencent.devops.process.utils.PIPELINE_START_PIPELINE_USER_ID
import com.tencent.devops.process.utils.PIPELINE_START_TYPE
import com.tencent.devops.process.utils.PIPELINE_START_USER_ID
import com.tencent.devops.process.utils.PIPELINE_START_USER_NAME
import com.tencent.devops.process.utils.PIPELINE_START_WEBHOOK_USER_ID
import com.tencent.devops.process.utils.PIPELINE_VERSION
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import javax.ws.rs.NotFoundException
import javax.ws.rs.core.Response
import javax.ws.rs.core.UriBuilder

/**
 *
 * @version 1.0
 */
@Service
class PipelineBuildService(
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val pipelineInterceptorChain: PipelineInterceptorChain,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val redisOperation: RedisOperation,
    private val buildDetailService: PipelineBuildDetailService,
    private val jmxApi: ProcessJmxApi,
    private val pipelinePermissionService: PipelinePermissionService,
    private val buildStartupParamService: BuildStartupParamService,
    private val paramService: ParamService,
    private val pipelineBuildQualityService: PipelineBuildQualityService,
    private val rabbitTemplate: RabbitTemplate
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PipelineBuildService::class.java)
        private val NO_LIMIT_CHANNEL = listOf(ChannelCode.CODECC)
    }

    private fun filterParams(
        userId: String?,
        projectId: String,
        pipelineId: String,
        params: List<BuildFormProperty>
    ): List<BuildFormProperty> {
        return paramService.filterParams(userId, projectId, pipelineId, params)
    }

    fun buildManualStartupInfo(
        userId: String?,
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): BuildManualStartupInfo {

        if (checkPermission) { // 不用校验查看权限，只校验执行权限
            pipelinePermissionService.validPipelinePermission(
                userId = userId!!,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限启动流水线($pipelineId)"
            )
        }

        pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId))

        val model = getModel(projectId, pipelineId)

        val container = model.stages[0].containers[0] as TriggerContainer

        var canManualStartup = false
        var canElementSkip = false
        var useLatestParameters = false
        run lit@{
            container.elements.forEach {
                if (it is ManualTriggerElement && it.isElementEnable()) {
                    canManualStartup = true
                    canElementSkip = it.canElementSkip ?: false
                    useLatestParameters = it.useLatestParameters ?: false
                    return@lit
                }
            }
        }

        // 当使用最近一次参数进行构建的时候，获取并替换container.params中的defaultValue值
        if (useLatestParameters) {
            // 获取最后一次的构建id
            val lastTimeBuildInfo = pipelineRuntimeService.getLastTimeBuild(pipelineId)
            if (lastTimeBuildInfo != null) {
                val latestParamsStr = buildStartupParamService.getParam(lastTimeBuildInfo.buildId)
                // 为空的时候不处理
                if (latestParamsStr != null) {
                    val latestParams =
                        JsonUtil.to(latestParamsStr, object : TypeReference<MutableMap<String, Any>>() {})
                    container.params.forEach { param ->
                        val realValue = latestParams[param.id]
                        if (realValue != null) {
                            // 有上一次的构建参数的时候才设置成默认值，否者依然使用默认值。
                            // 当值是boolean类型的时候，需要转为boolean类型
                            if (param.defaultValue is Boolean) {
                                param.defaultValue = realValue.toString().toBoolean()
                            } else {
                                param.defaultValue = realValue
                            }
                        }
                    }
                }
            }
        }

        val params = filterParams(
            userId = if (checkPermission && userId != null) userId else null,
            projectId = projectId,
            pipelineId = pipelineId,
            params = container.params
        )

        return BuildManualStartupInfo(canManualStartup, canElementSkip, params)
    }

    fun getBuildParameters(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String
    ): List<BuildParameters> {

        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限获取流水线($pipelineId)信息"
        )

        return try {
            val startupParam = buildStartupParamService.getParam(buildId)
            if (startupParam == null || startupParam.isEmpty()) {
                emptyList()
            } else {
                try {
                    val map: Map<String, Any> = JsonUtil.toMap(startupParam)
                    map.map { transform ->
                        BuildParameters(transform.key, transform.value)
                    }.toList().filter { !it.key.startsWith(SkipElementUtils.prefix) }
                } catch (e: Exception) {
                    logger.warn("Fail to convert the parameters($startupParam) to map of build($buildId)", e)
                    throw e
                }
            }
        } catch (e: NotFoundException) {
            return emptyList()
        }
    }

    fun retry(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        taskId: String? = null,
        isMobile: Boolean = false,
        channelCode: ChannelCode? = ChannelCode.BS,
        checkPermission: Boolean? = true
    ): String {
        if (checkPermission!!) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限重启流水线($pipelineId)"
            )
        }

        val redisLock = BuildIdLock(redisOperation = redisOperation, buildId = buildId)
        try {

            redisLock.lock()

            val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
                ?: throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                    defaultMessage = "构建任务${buildId}不存在",
                    params = arrayOf(buildId))

            if (!BuildStatus.isFailure(buildInfo.status)) {
                throw ErrorCodeException(
                    errorCode = ProcessMessageCode.ERROR_DUPLICATE_BUILD_RETRY_ACT,
                    defaultMessage = "重试已经启动，忽略重复的请求"
                )
            }

            val model = getModel(projectId = projectId, pipelineId = pipelineId, version = buildInfo.version)

            val container = model.stages[0].containers[0] as TriggerContainer

            var canManualStartup = false
            run lit@{
                container.elements.forEach {
                    if (it is ManualTriggerElement && it.isElementEnable()) {
                        canManualStartup = true
                        return@lit
                    }
                }
            }
            if (!canManualStartup) {
                throw ErrorCodeException(defaultMessage = "该流水线不能手动启动",
                    errorCode = ProcessMessageCode.DENY_START_BY_MANUAL)
            }
            val params = mutableMapOf<String, Any>()
            if (!taskId.isNullOrBlank()) {
                // job/task级重试，获取buildVariable构建参数，恢复环境变量
                params.putAll(pipelineRuntimeService.getAllVariable(buildId))
                // job/task级重试
                run {
                    model.stages.forEach { s ->
                        s.containers.forEach { c ->
                            val pos = if (c.id == taskId) 0 else -1 // 容器job级别的重试，则找job的第一个原子
                            c.elements.forEachIndexed { index, element ->
                                if (index == pos) {
                                    params[PIPELINE_RETRY_START_TASK_ID] = element.id!!
                                    return@run
                                }
                                if (element.id == taskId) {
                                    params[PIPELINE_RETRY_START_TASK_ID] = taskId!!
                                    return@run
                                }
                            }
                        }
                    }
                }

                params[PIPELINE_RETRY_COUNT] = if (params[PIPELINE_RETRY_COUNT] != null) {
                    params[PIPELINE_RETRY_COUNT].toString().toInt() + 1
                } else {
                    1
                }
            } else {
                // 完整构建重试
                try {
                    val startupParam = buildStartupParamService.getParam(buildId)
                    if (startupParam != null && startupParam.isNotEmpty()) {
                        params.putAll(JsonUtil.toMap(startupParam))
                    }
                } catch (e: Exception) {
                    logger.warn("Fail to get the startup param for the build($buildId)", e)
                }
                // 假如之前构建有原子级重试，则清除掉。因为整个流水线重试的是一个新的构建了(buildId)。
                params.remove(PIPELINE_RETRY_COUNT)
            }

            params[PIPELINE_START_USER_ID] = userId
            params[PIPELINE_START_TYPE] = StartType.MANUAL.name
            params[PIPELINE_RETRY_BUILD_ID] = buildId

            val readyToBuildPipelineInfo =
                pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
                    ?: throw ErrorCodeException(
                        statusCode = Response.Status.NOT_FOUND.statusCode,
                        errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                        defaultMessage = "流水线不存在",
                        params = arrayOf(buildId))

            return startPipeline(
                userId = userId,
                readyToBuildPipelineInfo = readyToBuildPipelineInfo,
                startType = StartType.MANUAL,
                startParams = params,
                channelCode = channelCode ?: ChannelCode.BS,
                isMobile = isMobile,
                model = model,
                signPipelineVersion = buildInfo.version,
                frequencyLimit = true
            )
        } finally {
            redisLock.unlock()
        }
    }

    fun buildManualStartup(
        userId: String,
        startType: StartType,
        projectId: String,
        pipelineId: String,
        values: Map<String, String>,
        channelCode: ChannelCode,
        checkPermission: Boolean = true,
        isMobile: Boolean = false,
        startByMessage: String? = null
    ): String {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限启动流水线($pipelineId)"
            )
        }

        val readyToBuildPipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId))

        val startEpoch = System.currentTimeMillis()
        try {

            val model = getModel(projectId, pipelineId)

            /**
             * 验证流水线参数构建启动参数
             */
            val triggerContainer = model.stages[0].containers[0] as TriggerContainer

            if (startType == StartType.MANUAL) {
                var canManualStartup = false
                run lit@{
                    triggerContainer.elements.forEach {
                        if (it is ManualTriggerElement && it.isElementEnable()) {
                            canManualStartup = true
                            return@lit
                        }
                    }
                }

                if (!canManualStartup) {
                    throw ErrorCodeException(defaultMessage = "该流水线不能手动启动",
                        errorCode = ProcessMessageCode.DENY_START_BY_MANUAL)
                }
            }
            if (startType == StartType.REMOTE) {
                var canRemoteStartup = false
                run lit@{
                    triggerContainer.elements.forEach {
                        if (it is RemoteTriggerElement && it.isElementEnable()) {
                            canRemoteStartup = true
                            return@lit
                        }
                    }
                }

                if (!canRemoteStartup) {
                    throw ErrorCodeException(defaultMessage = "该流水线不能远程触发",
                        errorCode = ProcessMessageCode.DENY_START_BY_REMOTE)
                }
            }

            val startParams = mutableMapOf<String, Any>()

            triggerContainer.params.forEach {
                val v = values[it.id]
                if (v == null) {
                    if (it.required) {
                        throw ErrorCodeException(defaultMessage = "参数(${it.id})是必填启动参数",
                            errorCode = ProcessMessageCode.DENY_START_BY_REMOTE)
                    }
                    startParams[it.id] = it.defaultValue
                } else {
                    startParams[it.id] = v
                }
            }

            model.stages.forEachIndexed { index, stage ->
                if (index == 0) {
                    return@forEachIndexed
                }
                stage.containers.forEach { container ->
                    container.elements.forEach { e ->
                        values.forEach { value ->
                            val key = SkipElementUtils.getSkipElementVariableName(e.id)
                            if (value.key == key && value.value == "true") {
                                logger.info("${e.id} will be skipped.")
                                startParams[key] = "true"
                            }
                        }
                    }
                }
            }

            return startPipeline(
                userId = userId,
                readyToBuildPipelineInfo = readyToBuildPipelineInfo,
                startType = startType,
                startParams = startParams,
                channelCode = channelCode,
                isMobile = isMobile,
                model = model
            )
        } finally {
            logger.info("It take(${System.currentTimeMillis() - startEpoch})ms to start pipeline($pipelineId)")
        }
    }

    fun subpipelineStartup(
        userId: String,
        startType: StartType,
        projectId: String,
        parentPipelineId: String,
        parentBuildId: String,
        parentTaskId: String,
        pipelineId: String,
        channelCode: ChannelCode,
        parameters: Map<String, Any>,
        checkPermission: Boolean = true,
        isMobile: Boolean = false
    ): String {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限启动流水线($pipelineId)"
            )
        }
        val readyToBuildPipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在")

        val startEpoch = System.currentTimeMillis()
        try {

            val model = getModel(projectId, pipelineId, readyToBuildPipelineInfo.version)

            /**
             * 验证流水线参数构建启动参数
             */
            val triggerContainer = model.stages[0].containers[0] as TriggerContainer

            val startParams = mutableMapOf<String, Any>()
            startParams.putAll(parameters)

            triggerContainer.params.forEach {
                if (startParams.containsKey(it.id)) {
                    return@forEach
                }
                startParams[it.id] = it.defaultValue
            }
            startParams[PIPELINE_START_PIPELINE_USER_ID] = userId
            startParams[PIPELINE_START_PARENT_PIPELINE_ID] = parentPipelineId
            startParams[PIPELINE_START_PARENT_BUILD_ID] = parentBuildId
            startParams[PIPELINE_START_PARENT_BUILD_TASK_ID] = parentTaskId
            // 子流水线的调用不受频率限制
            val subBuildId = startPipeline(
                userId = readyToBuildPipelineInfo.lastModifyUser,
                readyToBuildPipelineInfo = readyToBuildPipelineInfo,
                startType = startType,
                startParams = startParams,
                channelCode = channelCode,
                isMobile = isMobile,
                model = model,
                signPipelineVersion = null,
                frequencyLimit = false
            )
            // 更新父流水线关联子流水线构建id
            pipelineRuntimeService.updateTaskSubBuildId(
                buildId = parentBuildId,
                taskId = parentTaskId,
                subBuildId = subBuildId
            )
            return subBuildId
        } finally {
            logger.info("It take(${System.currentTimeMillis() - startEpoch})ms to start sub-pipeline($pipelineId)")
        }
    }

    /**
     * 定时触发
     */
    fun timerTriggerPipelineBuild(
        userId: String,
        projectId: String,
        pipelineId: String,
        parameters: Map<String, Any> = emptyMap(),
        checkPermission: Boolean = true
    ): String? {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限启动流水线($pipelineId)"
            )
        }
        val readyToBuildPipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
            ?: return null
        val startEpoch = System.currentTimeMillis()
        try {

            val model = getModel(projectId, pipelineId, readyToBuildPipelineInfo.version)

            /**
             * 验证流水线参数构建启动参数
             */
            val triggerContainer = model.stages[0].containers[0] as TriggerContainer

            val startParams = mutableMapOf<String, Any>()
            startParams.putAll(parameters)

            triggerContainer.params.forEach {
                if (startParams.containsKey(it.id)) {
                    return@forEach
                }
                startParams[it.id] = it.defaultValue
            }
            // 子流水线的调用不受频率限制
            return startPipeline(
                userId = userId,
                readyToBuildPipelineInfo = readyToBuildPipelineInfo,
                startType = StartType.TIME_TRIGGER,
                startParams = startParams,
                channelCode = readyToBuildPipelineInfo.channelCode,
                isMobile = false,
                model = model,
                signPipelineVersion = null,
                frequencyLimit = false
            )
        } finally {
            logger.info("Timer| It take(${System.currentTimeMillis() - startEpoch})ms to start pipeline($pipelineId)")
        }
    }

    fun buildManualShutdown(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ) {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限停止流水线($pipelineId)"
            )
        }

        buildManualShutdown(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            userId = userId,
            channelCode = channelCode
        )
    }

    fun buildManualReview(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        elementId: String,
        params: ReviewParam,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ) {

        pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId))

        val model = pipelineRepositoryService.getModel(pipelineId) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在")

        val runtimeVars = pipelineRuntimeService.getAllVariable(buildId)
        model.stages.forEachIndexed { index, s ->
            if (index == 0) {
                return@forEachIndexed
            }
            s.containers.forEach { cc ->
                cc.elements.forEach { el ->
                    if (el is ManualReviewUserTaskElement && el.id == elementId) {
                        // Replace the review user with environment
                        val reviewUser = mutableListOf<String>()
                        el.reviewUsers.forEach { user ->
                            reviewUser.addAll(EnvUtils.parseEnv(user, runtimeVars).split(",").map { it.trim() }.toList())
                        }
                        params.params.forEach {
                            it.value = EnvUtils.parseEnv(it.value.toString(), runtimeVars)
                        }
//                        elementName = el.name
                        if (!reviewUser.contains(userId)) {
                            logger.warn("User does not have the permission to review, userId:($userId) - (${el.reviewUsers}|$runtimeVars) - ($reviewUser)")
                            throw ErrorCodeException(
                                statusCode = Response.Status.NOT_FOUND.statusCode,
                                errorCode = ProcessMessageCode.ERROR_QUALITY_REVIEWER_NOT_MATCH,
                                defaultMessage = "用户($userId)不在审核人员名单中",
                                params = arrayOf(userId)
                            )
                        }
                    }
                }
            }
        }
        logger.info("[$buildId]|buildManualReview|taskId=$elementId|userId=$userId|params=$params")
        pipelineRuntimeService.manualDealBuildTask(buildId, elementId, userId, params)
        if (params.status == ManualReviewAction.ABORT) {
            buildDetailService.updateBuildCancelUser(buildId, userId)
        }
    }

    fun goToReview(userId: String, projectId: String, pipelineId: String, buildId: String, elementId: String): ReviewParam {

        pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId))

        val model = pipelineRepositoryService.getModel(pipelineId) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在")

        val runtimeVars = pipelineRuntimeService.getAllVariable(buildId)
        model.stages.forEachIndexed { index, s ->
            if (index == 0) {
                return@forEachIndexed
            }
            s.containers.forEach { cc ->
                cc.elements.forEach { el ->
                    if (el is ManualReviewUserTaskElement && el.id == elementId) {
                        val reviewUser = mutableListOf<String>()
                        el.reviewUsers.forEach { user ->
                            reviewUser.addAll(EnvUtils.parseEnv(user, runtimeVars).split(",").map { it.trim() }.toList())
                        }
                        el.params.forEach { param ->
                            param.value = EnvUtils.parseEnv(param.value ?: "", runtimeVars)
                        }
                        el.desc = EnvUtils.parseEnv(el.desc ?: "", runtimeVars)
                        if (!reviewUser.contains(userId)) {
                            logger.warn("User does not have the permission to review, userId:($userId) - (${el.reviewUsers}|$runtimeVars) - ($reviewUser)")
                            throw ErrorCodeException(
                                statusCode = Response.Status.NOT_FOUND.statusCode,
                                errorCode = ProcessMessageCode.ERROR_QUALITY_REVIEWER_NOT_MATCH,
                                defaultMessage = "用户($userId)不在审核人员名单中",
                                params = arrayOf(userId)
                            )
                        }
                        val reviewParam =
                            ReviewParam(projectId, pipelineId, buildId, reviewUser, null, el.desc, "", el.params)
                        logger.info("reviewParam : $reviewParam")
                        return reviewParam
                    }
                }
            }
        }
        return ReviewParam()
    }

    fun serviceShutdown(projectId: String, pipelineId: String, buildId: String, channelCode: ChannelCode) {
        val redisLock = RedisLock(redisOperation, "process.pipeline.build.shutdown.$buildId", 10)
        try {
            redisLock.lock()

            val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)

            if (buildInfo == null) {
                logger.warn("[$buildId]|SERVICE_SHUTDOWN| not exist")
                return
            } else {
                if (buildInfo.parentBuildId != null && buildInfo.parentBuildId != buildId) {
                    if (StartType.PIPELINE.name == buildInfo.trigger) {
                        if (buildInfo.parentTaskId != null) {
                            val superPipeline = pipelineRuntimeService.getBuildInfo(buildInfo.parentBuildId!!)
                            if (superPipeline != null) {
                                logger.info("[$pipelineId]|SERVICE_SHUTDOWN|super_build=${superPipeline.buildId}|super_pipeline=${superPipeline.pipelineId}")
                                serviceShutdown(
                                    projectId,
                                    superPipeline.pipelineId,
                                    superPipeline.buildId,
                                    channelCode
                                )
                                return
                            }
                        }
                    }
                }
            }

            try {
                pipelineRuntimeService.cancelBuild(
                    projectId,
                    pipelineId,
                    buildId,
                    buildInfo.startUser,
                    BuildStatus.FAILED
                )
                buildDetailService.updateBuildCancelUser(buildId, buildInfo.startUser)
                logger.info("Cancel the pipeline($pipelineId) of instance($buildId) by the user(${buildInfo.startUser})")
            } catch (t: Throwable) {
                logger.warn("Fail to shutdown the build($buildId) of pipeline($pipelineId)", t)
            }
        } finally {
            redisLock.unlock()
        }
    }

    fun getBuildDetail(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): ModelDetail {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)详情"
            )
        }

        return getBuildDetail(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            channelCode = channelCode,
            checkPermission = checkPermission
        )
    }

    fun getBuildDetail(
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): ModelDetail {

        return buildDetailService.get(buildId) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
        errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
        defaultMessage = "流水线编排不存在")
    }

    fun getBuildDetailByBuildNo(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildNo: Int,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): ModelDetail {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限获取流水线($pipelineId)详情"
        )
        val buildId = pipelineRuntimeService.getBuildIdbyBuildNo(projectId, pipelineId, buildNo)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建号($buildNo)不存在",
                params = arrayOf("buildNo=$buildNo"))
        return getBuildDetail(projectId, pipelineId, buildId, channelCode, checkPermission)
    }

    fun goToLatestFinishedBuild(
        userId: String,
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): Response {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)详情"
            )
        }
        val buildId = pipelineRuntimeService.getLatestFinishedBuildId(pipelineId)
        val apiDomain = HomeHostUtil.innerServerHost()
        val redirectURL = when (buildId) {
            null -> "$apiDomain/console/pipeline/$projectId/$pipelineId/history"
            else -> "$apiDomain/console/pipeline/$projectId/$pipelineId/detail/$buildId"
        }
        val uri = UriBuilder.fromUri(redirectURL).build()
        return Response.temporaryRedirect(uri).build()
    }

    fun getBuildStatus(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): BuildHistory {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId,
                projectId,
                pipelineId,
                AuthPermission.VIEW,
                "用户（$userId) 无权限获取流水线($pipelineId)构建状态"
            )
        }

        val buildHistories = pipelineRuntimeService.getBuildHistoryByIds(setOf(buildId))

        if (buildHistories.isEmpty()) {
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId))
        }
        return buildHistories[0]
    }

    fun getBuildStatusWithVars(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): BuildHistoryWithVars {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)构建状态"
            )
        }

        val buildHistories = pipelineRuntimeService.getBuildHistoryByIds(setOf(buildId))

        if (buildHistories.isEmpty()) {
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId))
        }
        val buildHistory = buildHistories[0]
        val variables = pipelineRuntimeService.getAllVariable(buildId)
        return BuildHistoryWithVars(
            id = buildHistory.id,
            userId = buildHistory.userId,
            trigger = buildHistory.trigger,
            buildNum = buildHistory.buildNum,
            pipelineVersion = buildHistory.pipelineVersion,
            startTime = buildHistory.startTime,
            endTime = buildHistory.endTime,
            status = buildHistory.status,
            deleteReason = buildHistory.deleteReason,
            currentTimestamp = buildHistory.currentTimestamp,
            isMobileStart = buildHistory.isMobileStart,
            material = buildHistory.material,
            queueTime = buildHistory.queueTime,
            artifactList = buildHistory.artifactList,
            remark = buildHistory.remark,
            totalTime = buildHistory.totalTime,
            executeTime = buildHistory.executeTime,
            buildParameters = buildHistory.buildParameters,
            webHookType = buildHistory.webHookType,
            startType = buildHistory.startType,
            recommendVersion = buildHistory.recommendVersion,
            variables = variables
        )
    }

    fun getBuildVars(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        checkPermission: Boolean
    ): Result<BuildHistoryVariables> {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)构建变量"
            )
        }

        val buildHistories = pipelineRuntimeService.getBuildHistoryByIds(setOf(buildId))

        if (buildHistories.isEmpty()) {
            return MessageCodeUtil.generateResponseDataObject(ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID, arrayOf(buildId))
        }

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
            ?: return MessageCodeUtil.generateResponseDataObject(
                ProcessMessageCode.ERROR_NO_PIPELINE_EXISTS_BY_ID,
                arrayOf(buildId)
            )

        val allVariable = pipelineRuntimeService.getAllVariable(buildId)

        return Result(
            BuildHistoryVariables(
                id = buildHistories[0].id,
                userId = buildHistories[0].userId,
                trigger = buildHistories[0].trigger,
                pipelineName = pipelineInfo.pipelineName,
                buildNum = buildHistories[0].buildNum ?: 1,
                pipelineVersion = buildHistories[0].pipelineVersion,
                status = buildHistories[0].status,
                startTime = buildHistories[0].startTime,
                endTime = buildHistories[0].endTime,
                variables = allVariable
            )
        )
    }

    fun getBatchBuildStatus(
        projectId: String,
        buildIdSet: Set<String>,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): List<BuildHistory> {
        val buildHistories = pipelineRuntimeService.getBuildHistoryByIds(buildIdSet)

        if (buildHistories.isEmpty()) {
            return emptyList()
        }
        return buildHistories
    }

    fun getHistoryBuild(
        userId: String?,
        projectId: String,
        pipelineId: String,
        page: Int?,
        pageSize: Int?,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): BuildHistoryPage<BuildHistory> {
        val pageNotNull = page ?: 0
        val pageSizeNotNull = pageSize ?: 1000
        val sqlLimit =
            if (pageSizeNotNull != -1) PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull) else null
        val offset = sqlLimit?.offset ?: 0
        val limit = sqlLimit?.limit ?: 1000

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId))

        val apiStartEpoch = System.currentTimeMillis()
        try {
            if (checkPermission) {
                pipelinePermissionService.validPipelinePermission(
                    userId = userId!!,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    permission = AuthPermission.VIEW,
                    message = "用户（$userId) 无权限获取流水线($pipelineId)历史构建"
                )
            }

            val newTotalCount = pipelineRuntimeService.getPipelineBuildHistoryCount(projectId, pipelineId)
            val newHistoryBuilds = pipelineRuntimeService.listPipelineBuildHistory(projectId, pipelineId, offset, limit)
            val buildHistories = mutableListOf<BuildHistory>()
            buildHistories.addAll(newHistoryBuilds)
            val count = newTotalCount + 0L
            // 获取流水线版本号
            val result = BuildHistoryWithPipelineVersion(
                history = SQLPage(count, buildHistories),
                hasDownloadPermission = !checkPermission || pipelinePermissionService.checkPipelinePermission(
                    userId = userId!!,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    permission = AuthPermission.EXECUTE
                ),
                pipelineVersion = pipelineInfo.version
            )
            return BuildHistoryPage(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = result.history.count,
                records = result.history.records,
                hasDownloadPermission = result.hasDownloadPermission,
                pipelineVersion = result.pipelineVersion
            )
        } finally {
            jmxApi.execute(ProcessJmxApi.LIST_NEW_BUILDS_DETAIL, System.currentTimeMillis() - apiStartEpoch)
        }
    }

    fun getHistoryBuild(
        userId: String?,
        projectId: String,
        pipelineId: String,
        page: Int?,
        pageSize: Int?,
        materialAlias: List<String>?,
        materialUrl: String?,
        materialBranch: List<String>?,
        materialCommitId: String?,
        materialCommitMessage: String?,
        status: List<BuildStatus>?,
        trigger: List<StartType>?,
        queueTimeStartTime: Long?,
        queueTimeEndTime: Long?,
        startTimeStartTime: Long?,
        startTimeEndTime: Long?,
        endTimeStartTime: Long?,
        endTimeEndTime: Long?,
        totalTimeMin: Long?,
        totalTimeMax: Long?,
        remark: String?,
        buildNoStart: Int?,
        buildNoEnd: Int?
    ): BuildHistoryPage<BuildHistory> {
        val pageNotNull = page ?: 0
        val pageSizeNotNull = pageSize ?: 1000
        val sqlLimit =
            if (pageSizeNotNull != -1) PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull) else null
        val offset = sqlLimit?.offset ?: 0
        val limit = sqlLimit?.limit ?: 1000

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, ChannelCode.BS)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId))

        val apiStartEpoch = System.currentTimeMillis()
        try {
            pipelinePermissionService.validPipelinePermission(
                userId = userId!!,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)历史构建"
            )

            val newTotalCount = pipelineRuntimeService.getPipelineBuildHistoryCount(
                projectId = projectId,
                pipelineId = pipelineId,
                materialAlias = materialAlias,
                materialUrl = materialUrl,
                materialBranch = materialBranch,
                materialCommitId = materialCommitId,
                materialCommitMessage = materialCommitMessage,
                status = status,
                trigger = trigger,
                queueTimeStartTime = queueTimeStartTime,
                queueTimeEndTime = queueTimeEndTime,
                startTimeStartTime = startTimeStartTime,
                startTimeEndTime = startTimeEndTime,
                endTimeStartTime = endTimeStartTime,
                endTimeEndTime = endTimeEndTime,
                totalTimeMin = totalTimeMin,
                totalTimeMax = totalTimeMax,
                remark = remark,
                buildNoStart = buildNoStart,
                buildNoEnd = buildNoEnd
            )

            val newHistoryBuilds = pipelineRuntimeService.listPipelineBuildHistory(
                projectId = projectId,
                pipelineId = pipelineId,
                offset = offset,
                limit = limit,
                materialAlias = materialAlias,
                materialUrl = materialUrl,
                materialBranch = materialBranch,
                materialCommitId = materialCommitId,
                materialCommitMessage = materialCommitMessage,
                status = status,
                trigger = trigger,
                queueTimeStartTime = queueTimeStartTime,
                queueTimeEndTime = queueTimeEndTime,
                startTimeStartTime = startTimeStartTime,
                startTimeEndTime = startTimeEndTime,
                endTimeStartTime = endTimeStartTime,
                endTimeEndTime = endTimeEndTime,
                totalTimeMin = totalTimeMin,
                totalTimeMax = totalTimeMax,
                remark = remark,
                buildNoStart = buildNoStart,
                buildNoEnd = buildNoEnd
            )
            val buildHistories = mutableListOf<BuildHistory>()
            buildHistories.addAll(newHistoryBuilds)
            val count = newTotalCount + 0L
            // 获取流水线版本号
            val result = BuildHistoryWithPipelineVersion(
                history = SQLPage(count, buildHistories),
                hasDownloadPermission = pipelinePermissionService.checkPipelinePermission(
                    userId = userId,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    permission = AuthPermission.EXECUTE
                ),
                pipelineVersion = pipelineInfo.version
            )
            return BuildHistoryPage(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = result.history.count,
                records = result.history.records,
                hasDownloadPermission = result.hasDownloadPermission,
                pipelineVersion = result.pipelineVersion
            )
        } finally {
            jmxApi.execute(ProcessJmxApi.LIST_NEW_BUILDS_DETAIL, System.currentTimeMillis() - apiStartEpoch)
        }
    }

    fun updateRemark(userId: String, projectId: String, pipelineId: String, buildId: String, remark: String?) {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.EDIT,
            message = "用户（$userId) 无权限修改流水线($pipelineId)历史构建"
        )
        pipelineRuntimeService.updateBuildRemark(projectId, pipelineId, buildId, remark)
    }

    fun getHistoryConditionStatus(userId: String, projectId: String, pipelineId: String): List<IdValue> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        val result = mutableListOf<IdValue>()
        BuildStatus.values().filter { it.visiable }.forEach {
            result.add(IdValue(it.name, MessageCodeUtil.getMessageByLocale(it.statusName, it.name)))
        }
        return result
    }

    fun getHistoryConditionTrigger(userId: String, projectId: String, pipelineId: String): List<IdValue> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        return StartType.getStartTypeMap()
    }

    fun getHistoryConditionRepo(userId: String, projectId: String, pipelineId: String): List<String> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        return pipelineRuntimeService.getHistoryConditionRepo(projectId, pipelineId)
    }

    fun getHistoryConditionBranch(
        userId: String,
        projectId: String,
        pipelineId: String,
        alias: List<String>?
    ): List<String> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        return pipelineRuntimeService.getHistoryConditionBranch(projectId, pipelineId, alias)
    }

    fun serviceBuildBasicInfo(buildId: String): BuildBasicInfo {
        val build = pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId))
        return BuildBasicInfo(
            buildId = buildId,
            projectId = build.projectId,
            pipelineId = build.pipelineId,
            pipelineVersion = build.version
        )
    }

    fun batchServiceBasic(buildIds: Set<String>): Map<String, BuildBasicInfo> {
        val buildBasicInfoMap = pipelineRuntimeService.getBuildBasicInfoByIds(buildIds)
        if (buildBasicInfoMap.isEmpty()) {
            return emptyMap()
        }
        return buildBasicInfoMap
    }

    fun getSingleHistoryBuild(
        projectId: String,
        pipelineId: String,
        buildNum: Int,
        channelCode: ChannelCode
    ): BuildHistory? {
        val statusSet = mutableSetOf<BuildStatus>()
        if (buildNum == -1) {
            BuildStatus.values().forEach { status ->
                if (BuildStatus.isFinish(status)) {
                    statusSet.add(status)
                } else if (BuildStatus.isRunning(status)) {
                    statusSet.add(status)
                }
            }
        }
        val buildHistory = pipelineRuntimeService.getBuildHistoryByBuildNum(
            projectId = projectId,
            pipelineId = pipelineId,
            buildNum = buildNum,
            statusSet = statusSet
        )
        logger.info("[$pipelineId]|buildHistory=$buildHistory")
        return buildHistory
    }

    fun getModel(projectId: String, pipelineId: String, version: Int? = null) =
        pipelineRepositoryService.getModel(pipelineId, version) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在")

    private fun buildManualShutdown(
        projectId: String,
        pipelineId: String,
        buildId: String,
        userId: String,
        channelCode: ChannelCode
    ) {

        val redisLock = BuildIdLock(redisOperation = redisOperation, buildId = buildId)
        try {
            redisLock.lock()

            val modelDetail = buildDetailService.get(buildId)
                ?: return
            val alreadyCancelUser = modelDetail.cancelUserId

            if (!alreadyCancelUser.isNullOrBlank()) {
                logger.warn("The build $buildId of project $projectId already cancel by user $alreadyCancelUser")
                throw ErrorCodeException(
                    errorCode = ProcessMessageCode.CANCEL_BUILD_BY_OTHER_USER,
                    defaultMessage = "流水线已经被${alreadyCancelUser}取消构建",
                    params = arrayOf(userId))
            }

            val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)

            if (pipelineInfo == null) {
                logger.warn("The pipeline($pipelineId) of project($projectId) is not exist")
                return
            }
            if (pipelineInfo.channelCode != channelCode) {
                return
            }

            val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
            if (buildInfo == null) {
                logger.warn("The build($buildId) of pipeline($pipelineId) is not exist")
                throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                    defaultMessage = "构建任务${buildId}不存在",
                    params = arrayOf(buildId))
            }

            val model = getModel(projectId = projectId, pipelineId = pipelineId, version = buildInfo.version)
            val tasks = getRunningTask(projectId, buildId)
            var isPrepareEnv = true
            model.stages.forEachIndexed { index, stage ->
                if (index == 0) {
                    return@forEachIndexed
                }
                stage.containers.forEach { container ->
                    container.elements.forEach { e ->
                        tasks.forEach { task ->
                            val taskId = task["taskId"] ?: ""
                            val containerId = task["containerId"] ?: ""
                            val status = task["status"] ?: ""
                            if (taskId == e.id) {
                                isPrepareEnv = false
                                logger.info("build($buildId) shutdown by $userId, taskId: $taskId, status: $status")
                                LogUtils.addYellowLine(
                                    rabbitTemplate = rabbitTemplate,
                                    buildId = buildId,
                                    message = "流水线被用户终止，操作人:$userId",
                                    tag = taskId,
                                    jobId = containerId,
                                    executeCount = 1
                                )
                                LogUtils.addFoldEndLine(
                                    rabbitTemplate = rabbitTemplate,
                                    buildId = buildId,
                                    tagName = "${e.name}-[$taskId]",
                                    tag = taskId,
                                    jobId = containerId,
                                    executeCount = 1
                                )
                            }
                        }
                    }
                }
            }

            if (isPrepareEnv) {
                LogUtils.addYellowLine(rabbitTemplate, buildId, "流水线被用户终止，操作人:$userId", "", "", 1)
            }

            try {
                pipelineRuntimeService.cancelBuild(projectId, pipelineId, buildId, userId, BuildStatus.CANCELED)
                buildDetailService.updateBuildCancelUser(buildId, userId)
                logger.info("Cancel the pipeline($pipelineId) of instance($buildId) by the user($userId)")
            } catch (t: Throwable) {
                logger.warn("Fail to shutdown the build($buildId) of pipeline($pipelineId)", t)
            }
        } finally {
            redisLock.unlock()
        }
    }

    private fun getRunningTask(projectId: String, buildId: String): List<Map<String, String>> {
        return pipelineRuntimeService.getRunningTask(projectId, buildId)
    }

    fun startPipeline(
        userId: String,
        readyToBuildPipelineInfo: PipelineInfo,
        startType: StartType,
        startParams: Map<String, Any>,
        channelCode: ChannelCode,
        isMobile: Boolean,
        model: Model,
        signPipelineVersion: Int? = null, // 指定的版本
        frequencyLimit: Boolean = true
    ): String {

        val redisLock = RedisLock(redisOperation, "build:limit:${readyToBuildPipelineInfo.pipelineId}", 5L)
        try {
            if (frequencyLimit && channelCode !in NO_LIMIT_CHANNEL && !redisLock.tryLock()) {
                throw ErrorCodeException(errorCode = ProcessMessageCode.ERROR_START_BUILD_FREQUENT_LIMIT,
                    defaultMessage = "不能太频繁启动构建")
            }

            // 如果指定了版本号，则设置指定的版本号
            readyToBuildPipelineInfo.version = signPipelineVersion ?: readyToBuildPipelineInfo.version

            val fullModel = pipelineBuildQualityService.fillingRuleInOutElement(
                projectId = readyToBuildPipelineInfo.projectId,
                pipelineId = readyToBuildPipelineInfo.pipelineId,
                startParams = startParams,
                model = model
            )

            val interceptResult = pipelineInterceptorChain.filter(
                InterceptData(readyToBuildPipelineInfo, fullModel, startType)
            )

            if (interceptResult.isNotOk()) {
                // 发送排队失败的事件
                logger.error("[${readyToBuildPipelineInfo.pipelineId}]|START_PIPELINE_$startType|流水线启动失败:[${interceptResult.message}]")
                throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = interceptResult.status.toString(),
                    defaultMessage = "流水线启动失败![${interceptResult.message}]"
                )
            }

            val params = startParams.plus(
                mapOf(
                    PIPELINE_VERSION to readyToBuildPipelineInfo.version,
                    PIPELINE_START_USER_ID to userId,
                    PIPELINE_START_TYPE to startType.name,
                    PIPELINE_START_CHANNEL to channelCode.name,
                    PIPELINE_START_MOBILE to isMobile,
                    PIPELINE_NAME to readyToBuildPipelineInfo.pipelineName
                )
            ).plus(
                when (startType) {
                    StartType.PIPELINE -> {
                        mapOf(
                            if (startParams[PIPELINE_START_PIPELINE_USER_ID] != null) {
                                PIPELINE_START_USER_NAME to startParams[PIPELINE_START_PIPELINE_USER_ID]!!
                            } else {
                                PIPELINE_START_USER_NAME to userId
                            }
                        )
                    }
                    StartType.MANUAL -> mapOf(
                        PIPELINE_START_USER_NAME to userId
                    )
                    StartType.WEB_HOOK -> mapOf(
                        if (startParams[PIPELINE_START_WEBHOOK_USER_ID] != null) {
                            PIPELINE_START_USER_NAME to startParams[PIPELINE_START_WEBHOOK_USER_ID]!!
                        } else {
                            PIPELINE_START_USER_NAME to userId
                        }
                    )
                    else -> {
                        mapOf(PIPELINE_START_USER_NAME to userId)
                    }
                }
            )

            val buildId = pipelineRuntimeService.startBuild(readyToBuildPipelineInfo, fullModel, params)
            if (startParams.isNotEmpty()) {
                buildStartupParamService.addParam(
                    projectId = readyToBuildPipelineInfo.projectId,
                    pipelineId = readyToBuildPipelineInfo.pipelineId,
                    buildId = buildId,
                    param = JsonUtil.toJson(startParams)
                )
            }

            logger.info("[${readyToBuildPipelineInfo.pipelineId}]|START_PIPELINE|startType=$startType|startParams=$startParams")

            return buildId
        } finally {
            if (readyToBuildPipelineInfo.channelCode !in NO_LIMIT_CHANNEL) redisLock.unlock()
        }
    }

    fun getPipelineLatestBuildByIds(projectId: String, pipelineIds: List<String>): Map<String, PipelineLatestBuild> {
        logger.info("getPipelineLatestBuildByIds: $projectId | $pipelineIds")

        return pipelineRuntimeService.getLatestBuild(projectId, pipelineIds)
    }

    fun workerBuildFinish(
        projectCode: String,
        pipelineId: String, /* pipelineId在agent请求的数据有值前不可用 */
        buildId: String,
        vmSeqId: String,
        simpleResult: SimpleResult
    ) {
        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId) ?: return
        if (BuildStatus.isFinish(buildInfo.status)) {
            logger.info("[$buildId]|The build is ${buildInfo.status}")
            return
        }

        val msg = if (simpleResult.success) {
            "构建任务对应的Agent进程已退出"
        } else {
            "构建任务对应的Agent进程已退出: ${simpleResult.message}"
        }
        logger.info("worker build($buildId|$vmSeqId|${simpleResult.success}) $msg")

        var stageId: String? = null
        var containerType = "vmBuild"
        val modelDetail = buildDetailService.get(buildId) ?: return
        run outer@{
            modelDetail.model.stages.forEach { stage ->
                stage.containers.forEach { c ->
                    if (c.id == vmSeqId) {
                        stageId = stage.id!!
                        containerType = c.getClassType()
                        return@outer
                    }
                }
            }
        }

        if (stageId.isNullOrBlank()) {
            logger.warn("[$buildId]|worker build finish|can not find stage")
            return
        }

        pipelineEventDispatcher.dispatch(
            PipelineBuildContainerEvent(
                source = "worker_build_finish",
                projectId = buildInfo.projectId,
                pipelineId = buildInfo.pipelineId,
                userId = buildInfo.startUser,
                buildId = buildId,
                stageId = stageId!!,
                containerId = vmSeqId,
                containerType = containerType,
                actionType = ActionType.TERMINATE,
                reason = msg
            )
        )
    }

    fun saveBuildVmInfo(projectId: String, pipelineId: String, buildId: String, vmSeqId: String, vmInfo: VmInfo) {
        pipelineRuntimeService.saveBuildVmInfo(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            vmSeqId = vmSeqId,
            vmInfo = vmInfo
        )
    }
}