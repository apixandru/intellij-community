// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.ZipperUpdater;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.util.Alarm;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Listens to file system events and notifies VcsDirtyScopeManagers responsible for changed files to mark these files dirty.
 */
public class VcsDirtyScopeVfsListener implements BulkFileListener, Disposable {
  @NotNull private final ProjectLevelVcsManager myVcsManager;

  private boolean myForbid; // for tests only

  @NotNull private final ZipperUpdater myZipperUpdater;
  private final List<FilesAndDirs> myQueue;
  private final Object myLock;
  @NotNull private final Runnable myDirtReporter;

  public VcsDirtyScopeVfsListener(@NotNull Project project,
                                  @NotNull ProjectLevelVcsManager vcsManager,
                                  @NotNull VcsDirtyScopeManager dirtyScopeManager) {
    myVcsManager = vcsManager;

    myLock = new Object();
    myQueue = new ArrayList<>();
    myDirtReporter = () -> {
      ArrayList<FilesAndDirs> list;
      synchronized (myLock) {
        list = new ArrayList<>(myQueue);
        myQueue.clear();
      }

      HashSet<FilePath> dirtyFiles = new HashSet<>();
      HashSet<FilePath> dirtyDirs = new HashSet<>();
      for (FilesAndDirs filesAndDirs : list) {
        dirtyFiles.addAll(filesAndDirs.dirtyFiles);
        dirtyDirs.addAll(filesAndDirs.dirtyDirs);
      }
      if (!dirtyFiles.isEmpty() || !dirtyDirs.isEmpty()) {
        dirtyScopeManager.filePathsDirty(dirtyFiles, dirtyDirs);
      }
    };
    myZipperUpdater = new ZipperUpdater(300, Alarm.ThreadToUse.POOLED_THREAD, this);
    Disposer.register(project, this);
    project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, this);
  }

  public static VcsDirtyScopeVfsListener getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, VcsDirtyScopeVfsListener.class);
  }

  public static void install(@NotNull Project project) {
    if (!project.isOpen()) {
      throw new RuntimeException("Already closed: " + project);
    }
    getInstance(project);
  }

  public void setForbid(boolean forbid) {
    assert ApplicationManager.getApplication().isUnitTestMode();
    myForbid = forbid;
  }

  @Override
  public void dispose() {
    synchronized (myLock) {
      myQueue.clear();
    }

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      //noinspection TestOnlyProblems
      waitForAsyncTaskCompletion();
    }
  }

  @TestOnly
  void waitForAsyncTaskCompletion() {
    myZipperUpdater.waitForAllExecuted(10, TimeUnit.SECONDS);
  }

  @Override
  public void before(@NotNull List<? extends VFileEvent> events) {
    if (myForbid || !myVcsManager.hasAnyMappings()) return;
    final FilesAndDirs dirtyFilesAndDirs = new FilesAndDirs();
    // collect files and directories - sources of events
    for (VFileEvent event : events) {
      if (event instanceof VFileCreateEvent) continue;
      final VirtualFile file = event.getFile();

      if (file == null || !file.isInLocalFileSystem()) {
        continue;
      }

      if (event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent || event instanceof VFilePropertyChangeEvent) {
        add(myVcsManager, dirtyFilesAndDirs, file);
      }
    }
    markDirtyOnPooled(dirtyFilesAndDirs);
  }

  @Override
  public void after(@NotNull List<? extends VFileEvent> events) {
    if (myForbid || !myVcsManager.hasAnyMappings()) return;
    final FilesAndDirs dirtyFilesAndDirs = new FilesAndDirs();
    // collect files and directories - sources of events
    for (VFileEvent event : events) {
      if (event instanceof VFileDeleteEvent) continue;

      final VirtualFile file = event.getFile();
      if (file == null || !file.isInLocalFileSystem()) {
        continue;
      }

      if (event instanceof VFileContentChangeEvent || event instanceof VFileCreateEvent || event instanceof VFileMoveEvent) {
        add(myVcsManager, dirtyFilesAndDirs, file);
      }
      else if (event instanceof VFileCopyEvent) {
        VFileCopyEvent copyEvent = (VFileCopyEvent)event;
        add(myVcsManager, dirtyFilesAndDirs, copyEvent.getNewParent().findChild(copyEvent.getNewChildName()));
      }
      else if (event instanceof VFilePropertyChangeEvent) {
        final VFilePropertyChangeEvent pce = (VFilePropertyChangeEvent)event;
        if (pce.isRename()) {
          // if a file was renamed, then the file is dirty and its parent directory is dirty too;
          // if a directory was renamed, all its children are recursively dirty, the parent dir is also dirty but not recursively.
          add(myVcsManager, dirtyFilesAndDirs, file);   // the file is dirty recursively
          addToFiles(myVcsManager, dirtyFilesAndDirs, file.getParent()); // directory is dirty alone. if parent is null - is checked in the method
        } else {
          addToFiles(myVcsManager, dirtyFilesAndDirs, file);
        }
      }
    }
    markDirtyOnPooled(dirtyFilesAndDirs);
  }

  private void markDirtyOnPooled(@NotNull FilesAndDirs dirtyFilesAndDirs) {
    if (!dirtyFilesAndDirs.isEmpty()) {
      synchronized (myLock) {
        myQueue.add(dirtyFilesAndDirs);
      }
      myZipperUpdater.queue(myDirtReporter);
    }
  }

  /**
   * Stores VcsDirtyScopeManagers and files and directories which should be marked dirty by them.
   * Files will be marked dirty, directories will be marked recursively dirty, so if you need to mark dirty a directory, but
   * not recursively, you should add it to files.
   */
  private static class FilesAndDirs {
    @NotNull HashSet<FilePath> dirtyFiles = new HashSet<>();
    @NotNull HashSet<FilePath> dirtyDirs = new HashSet<>();

    private boolean isEmpty() {
      return dirtyFiles.isEmpty() && dirtyDirs.isEmpty();
    }
  }

  private static void add(@NotNull ProjectLevelVcsManager vcsManager,
                          @NotNull FilesAndDirs filesAndDirs,
                          @Nullable VirtualFile file,
                          boolean addToFiles) {
    if (file == null) return;
    if (!vcsManager.isFileInContent(file)) return;

    boolean isDirectory = file.isDirectory();
    FilePath path = VcsUtil.getFilePath(file.getPath(), isDirectory);
    if (addToFiles || !isDirectory) {
      filesAndDirs.dirtyFiles.add(path);
    }
    else {
      filesAndDirs.dirtyDirs.add(path);
    }
  }

  private static void add(@NotNull ProjectLevelVcsManager vcsManager,
                          @NotNull FilesAndDirs filesAndDirs,
                          @Nullable VirtualFile file) {
    add(vcsManager, filesAndDirs, file, false);
  }

  private static void addToFiles(@NotNull ProjectLevelVcsManager vcsManager,
                                 @NotNull FilesAndDirs filesAndDirs,
                                 @Nullable VirtualFile file) {
    add(vcsManager, filesAndDirs, file, true);
  }
}
