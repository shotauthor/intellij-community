/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package org.jetbrains.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.ui.AppUIUtil
import com.intellij.util.exists
import com.intellij.util.systemIndependentPath
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.builtInWebServer.WebServerPathToFileManager
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.catchError
import org.jetbrains.concurrency.rejectedPromise
import org.jetbrains.io.isLocalOrigin
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern

private val NOT_FOUND = Promise.createError("not found")
private val LINE_AND_COLUMN = Pattern.compile("^(.*?)(?::(\\d+))?(?::(\\d+))?$")

/**
 * @api {get} /file Open file
 * @apiName file
 * @apiGroup Platform
 *
 * @apiParam {String} file The path of the file. Relative (to project base dir, VCS root, module source or content root) or absolute.
 * @apiParam {Integer} [line] The line number of the file (1-based).
 * @apiParam {Integer} [column] The column number of the file (1-based).
 * @apiParam {Boolean} [focused=true] Whether to focus project window.
 *
 * @apiExample {curl} Absolute path
 * curl http://localhost:63342/api/file//absolute/path/to/file.kt
 *
 * @apiExample {curl} Relative path
 * curl http://localhost:63342/api/file/relative/to/module/root/path/to/file.kt
 *
 * @apiExample {curl} With line and column
 * curl http://localhost:63342/api/file/relative/to/module/root/path/to/file.kt:100:34
 *
 * @apiExample {curl} Query parameters
 * curl http://localhost:63342/api/file?file=path/to/file.kt&line=100&column=34
 */
internal class OpenFileHttpService : RestService() {
  @Volatile private var refreshSessionId: Long = 0
  private val requests = ConcurrentLinkedQueue<OpenFileTask>()

  override fun getServiceName() = "file"

  override fun isMethodSupported(method: HttpMethod) = method === HttpMethod.GET || method === HttpMethod.POST

  override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
    val keepAlive = HttpUtil.isKeepAlive(request)
    val channel = context.channel()

    val apiRequest: OpenFileRequest
    if (request.method() === HttpMethod.POST) {
      apiRequest = gson.value.fromJson(RestService.createJsonReader(request), OpenFileRequest::class.java)
    }
    else {
      apiRequest = OpenFileRequest()
      apiRequest.file = StringUtil.nullize(RestService.getStringParameter("file", urlDecoder), true)
      apiRequest.line = RestService.getIntParameter("line", urlDecoder)
      apiRequest.column = RestService.getIntParameter("column", urlDecoder)
      apiRequest.focused = RestService.getBooleanParameter("focused", urlDecoder, true)
    }

    val prefixLength = 1 + RestService.PREFIX.length + 1 + serviceName.length + 1
    val path = urlDecoder.path()
    if (path.length > prefixLength) {
      val matcher = LINE_AND_COLUMN.matcher(path).region(prefixLength, path.length)
      RestService.LOG.assertTrue(matcher.matches())
      if (apiRequest.file == null) {
        apiRequest.file = matcher.group(1).trim { it <= ' ' }
      }
      if (apiRequest.line == -1) {
        apiRequest.line = StringUtilRt.parseInt(matcher.group(2), 1)
      }
      if (apiRequest.column == -1) {
        apiRequest.column = StringUtilRt.parseInt(matcher.group(3), 1)
      }
    }

    if (apiRequest.file == null) {
      RestService.sendStatus(HttpResponseStatus.BAD_REQUEST, keepAlive, channel)
      return null
    }

    openFile(apiRequest).done { RestService.sendStatus(HttpResponseStatus.OK, keepAlive, channel) }
      .rejected { throwable ->
        if (throwable === NOT_FOUND) {
          // don't expose file status if not local origin
          RestService.sendStatus(if (request.isLocalOrigin()) HttpResponseStatus.NOT_FOUND else HttpResponseStatus.OK, keepAlive, channel)
        }
        else {
          // todo send error
          RestService.sendStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR, keepAlive, channel)
          RestService.LOG.error(throwable)
        }
      }
    return null
  }

  fun openFile(request: OpenFileRequest): Promise<Void> {
    val path = FileUtil.expandUserHome(request.file!!)
    val file = Paths.get(FileUtil.toSystemDependentName(path))
    if (file.isAbsolute) {
      return openAbsolutePath(file, request)
    }

    // we don't want to call refresh for each attempt on findFileByRelativePath call, so, we do what ourSaveAndSyncHandlerImpl does on frame activation
    val queue = RefreshQueue.getInstance()
    queue.cancelSession(refreshSessionId)
    val mainTask = OpenFileTask(FileUtil.toCanonicalPath(FileUtil.toSystemIndependentName(path), '/'), request)
    requests.offer(mainTask)
    val session = queue.createSession(true, true, {
      while (true) {
        val task = requests.poll() ?: break
        task.promise.catchError {
          if (openRelativePath(task.path, task.request)) {
            task.promise.setResult(null)
          }
          else {
            task.promise.setError(NOT_FOUND)
          }
        }
      }
    }, ModalityState.NON_MODAL)

    session.addAllFiles(*ManagingFS.getInstance().localRoots)
    refreshSessionId = session.id
    session.launch()
    return mainTask.promise
  }

  override fun isAllowRequestOnlyFromLocalOrigin() = false
}

internal class OpenFileRequest {
  var file: String? = null
  // The line number of the file (1-based)
  var line = 0
  // The column number of the file (1-based)
  var column = 0

  var focused = true
}

private class OpenFileTask(internal val path: String, internal val request: OpenFileRequest) {
  internal val promise = AsyncPromise<Void>()
}

private fun navigate(project: Project?, file: VirtualFile, request: OpenFileRequest) {
  val effectiveProject = project ?: RestService.getLastFocusedOrOpenedProject() ?: ProjectManager.getInstance().defaultProject
  // OpenFileDescriptor line and column number are 0-based.
  OpenFileDescriptor(effectiveProject, file, Math.max(request.line - 1, 0), Math.max(request.column - 1, 0)).navigate(true)
  if (request.focused) {
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
  }
}

// path must be normalized
private fun openRelativePath(path: String, request: OpenFileRequest): Boolean {
  var virtualFile: VirtualFile? = null
  var project: Project? = null

  val projects = ProjectManager.getInstance().openProjects
  for (openedProject in projects) {
    openedProject.baseDir?.let {
      virtualFile = it.findFileByRelativePath(path)
    }

    if (virtualFile == null) {
      virtualFile = WebServerPathToFileManager.getInstance(openedProject).findVirtualFile(path)
    }
    if (virtualFile != null) {
      project = openedProject
      break
    }
  }

  if (virtualFile == null) {
    for (openedProject in projects) {
      for (vcsRoot in ProjectLevelVcsManager.getInstance(openedProject).allVcsRoots) {
        val root = vcsRoot.path
        if (root != null) {
          virtualFile = root.findFileByRelativePath(path)
          if (virtualFile != null) {
            project = openedProject
            break
          }
        }
      }
    }
  }

  virtualFile?.let {
    AppUIUtil.invokeLaterIfProjectAlive(project!!, Runnable { navigate(project, it, request) })
    return true
  }
  return false
}

private fun openAbsolutePath(file: Path, request: OpenFileRequest): Promise<Void> {
  if (!file.exists()) {
    return rejectedPromise(NOT_FOUND)
  }

  val promise = AsyncPromise<Void>()
  ApplicationManager.getApplication().invokeLater {
    promise.catchError {
      val virtualFile = runWriteAction {  LocalFileSystem.getInstance().refreshAndFindFileByPath(file.systemIndependentPath) }
      if (virtualFile == null) {
        promise.setError(NOT_FOUND)
      }
      else {
        navigate(ProjectUtil.guessProjectForContentFile(virtualFile), virtualFile, request)
        promise.setResult(null)
      }
    }
  }
  return promise
}
