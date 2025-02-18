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
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bk.devops.atom.utils.script

import com.tencent.bk.devops.atom.enums.CharsetType
import com.tencent.bk.devops.atom.utils.CommandLineUtils
import com.tencent.bk.devops.atom.utils.CommonUtil
import com.tencent.bk.devops.atom.utils.ScriptEnvUtils
import org.apache.commons.exec.CommandLine
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

object BatScriptUtil {

    // 
    private const val setEnv = ":setEnv\r\n" +
        "    set file_save_dir=\"##resultFile##\"\r\n" +
        "    echo %~1=%~2 >>%file_save_dir%\r\n" +
        "    set %~1=%~2\r\n" +
        "    goto:eof\r\n"

    // 
    private const val setGateValue = ":setGateValue\r\n" +
        "    set file_save_dir=\"##gateValueFile##\"\r\n" +
        "    echo %~1=%~2 >>%file_save_dir%\r\n" +
        "    set %~1=%~2\r\n" +
        "    goto:eof\r\n"

    private val logger = LoggerFactory.getLogger(BatScriptUtil::class.java)

    // 2021-06-11 batchScript需要过滤掉上下文产生的变量，防止注入到环境变量中
    private val specialKey = listOf("variables.", "settings.", "envs.", "ci.", "job.", "jobs.", "steps.")

    private val specialValue = listOf("\n", "\r")
    private val escapeValue = mapOf(
        "&" to "^&",
        "<" to "^<",
        ">" to "^>",
        "|" to "^|",
        "\"" to "\\\""
    )

    @Suppress("ALL")
    fun execute(
        script: String,
        buildId: String,
        runtimeVariables: Map<String, String>,
        dir: File,
        prefix: String = "",
        paramClassName: List<String>,
        errorMessage: String? = null,
        workspace: File = dir,
        print2Logger: Boolean = true,
        stepId: String? = null,
        charsetType: CharsetType? = null
    ): String {
        try {
            val file = getCommandFile(
                buildId = buildId,
                script = script,
                runtimeVariables = runtimeVariables,
                dir = dir,
                workspace = workspace,
                charsetType = charsetType,
                paramClassName = paramClassName
            )
            val command = "cmd.exe /C \"${file.canonicalPath}\""
            return CommandLineUtils.execute(
                cmdLine = CommandLine.parse(command),
                workspace = dir,
                print2Logger = print2Logger,
                prefix = prefix,
                executeErrorMessage = "",
                buildId = buildId,
                stepId = stepId,
                charSetType = charsetType
            )
        } catch (ignore: Throwable) {
            val errorInfo = errorMessage ?: "Fail to execute bat script"
            logger.warn(errorInfo, ignore)
            throw ignore
        }
    }

    @Suppress("ALL")
    fun getCommandFile(
        buildId: String,
        script: String,
        runtimeVariables: Map<String, String>,
        dir: File,
        workspace: File = dir,
        paramClassName: List<String>,
        charsetType: CharsetType? = null
    ): File {
        val file = Files.createTempFile(CommonUtil.getTmpDir(), "paas_build_script_", ".bat").toFile()
        file.deleteOnExit()

        val command = StringBuilder()

        command.append("@echo off")
            .append("\r\n")
            .append("set WORKSPACE=${workspace.absolutePath}\r\n")
            .append("set DEVOPS_BUILD_SCRIPT_FILE=${file.absolutePath}\r\n")
            .append("\r\n")

        runtimeVariables
//            .plus(CommonEnv.getCommonEnv()) // 
            .filterNot { specialEnv(it.key, it.value) || it.key in paramClassName }
            .forEach { (name, value) ->
                // 特殊保留字符转义
                val clean = escapeEnv(value)
                command.append("set $name=\"$clean\"\r\n") // 双引号防止变量值有空格而意外截断定义
                command.append("set $name=%$name:~1,-1%\r\n") // 去除双引号，防止被程序读到有双引号的变量值
            }

        command.append(script.replace("\n", "\r\n"))
            .append("\r\n")
            .append("exit")
            .append("\r\n")
            .append(
                setEnv.replace(
                    oldValue = "##resultFile##",
                    newValue = File(dir, ScriptEnvUtils.getEnvFile(buildId)).absolutePath
                )
            )
            .append(
                setGateValue.replace(
                    oldValue = "##gateValueFile##",
                    newValue = File(dir, ScriptEnvUtils.getQualityGatewayEnvFile()).canonicalPath
                )
            )

        val charset = when (charsetType) {
            CharsetType.UTF_8 -> Charsets.UTF_8
            CharsetType.GBK -> Charset.forName(CharsetType.GBK.name)
            else -> Charset.defaultCharset()
        }
        logger.info("The default charset is $charset")

        file.writeText(command.toString(), charset)
        CommonUtil.printTempFileInfo(file)
        return file
    }

    private fun specialEnv(key: String, value: String): Boolean {
        var match = false
        /*过滤处理特殊的key*/
        for (it in specialKey) {
            if (key.trim().startsWith(it)) {
                match = true
                break
            }
        }

        /*过滤处理特殊的value*/
        for (it in specialValue) {
            if (value.contains(it)) {
                match = true
                break
            }
        }
        return match
    }

    /*做好转义，避免意外*/
    private fun escapeEnv(value: String): String {
        var result = value
        escapeValue.forEach { (k, v) ->
            result = result.replace(k, v)
        }
        return result
    }
}
