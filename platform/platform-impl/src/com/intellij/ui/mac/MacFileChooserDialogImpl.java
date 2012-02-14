/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.ui.mac;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserUtil;
import com.intellij.openapi.fileChooser.MacFileChooserDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.mac.foundation.Foundation;
import com.intellij.ui.mac.foundation.ID;
import com.intellij.ui.mac.foundation.MacUtil;
import com.intellij.util.ArrayUtil;
import com.sun.jna.Callback;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author spleaner
 */
@SuppressWarnings("AssignmentToStaticFieldFromInstanceMethod")
public class MacFileChooserDialogImpl implements MacFileChooserDialog {
  private static final int OK = 1;

  private static JDialog myFakeDialog;
  private static List<String> myResultPaths;
  private static FileChooserDescriptor myChooserDescriptor;
  private static boolean myFileChooserActive = false;
  private static MacFileChooserCallback mySheetCallback = null;

  private static final Callback SHOULD_ENABLE_URL = new Callback() {
    @SuppressWarnings("UnusedDeclaration")
    public boolean callback(ID self, String selector, ID panel, ID url) {
      return true;
    }
  };

  private static final Callback SHOULD_SHOW_FILENAME_CALLBACK = new Callback() {
    @SuppressWarnings("UnusedDeclaration")
    public boolean callback(ID self, String selector, ID panel, ID filename) {
      if (filename == null || filename.intValue() == 0) return false;
      final String fileName = Foundation.toStringViaUTF8(filename);
      if (fileName == null) return false;
      final VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(fileName);
      return virtualFile == null || (virtualFile.isDirectory() || myChooserDescriptor.isFileSelectable(virtualFile));
    }
  };

  private static final Callback IS_VALID_FILENAME_CALLBACK = new Callback() {
    @SuppressWarnings("UnusedDeclaration")
    public boolean callback(ID self, String selector, ID panel, ID filename) {
      if (filename == null || filename.intValue() == 0) return false;
      final String fileName = Foundation.toStringViaUTF8(filename);
      if (fileName == null) return false;
      final VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(fileName);
      return virtualFile == null || (!virtualFile.isDirectory() || myChooserDescriptor.isFileSelectable(virtualFile));
    }
  };

  private static final Callback OPEN_PANEL_DID_END = new Callback() {
    @SuppressWarnings("UnusedDeclaration")
    public void callback(ID self, String selector, ID openPanelDidEnd, ID returnCode, ID contextInfo) {
      processResult(returnCode, openPanelDidEnd);

      try {
        if (myResultPaths != null) {
          final String[] paths = ArrayUtil.toStringArray(myResultPaths);
          final MacFileChooserCallback callback = mySheetCallback;
          //noinspection SSBasedInspection
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              callback.onChosen(FileChooserUtil.getFiles(paths));
            }
          });
        }
      }
      finally {
        myFileChooserActive = false;
        myResultPaths = null;
        mySheetCallback = null;
      }

      Foundation.cfRelease(self);
    }
  };

  private static final Callback MAIN_THREAD_RUNNABLE = new Callback() {
    @SuppressWarnings("UnusedDeclaration")
    public void callback(ID self, String selector, ID toSelect) {
      final ID nsOpenPanel = Foundation.getObjcClass("NSOpenPanel");
      final ID chooser = invoke(nsOpenPanel, "openPanel");

      invoke(chooser, "setPrompt:", Foundation.nsString("Choose"));
      invoke(chooser, "setCanChooseFiles:", myChooserDescriptor.isChooseFiles() || myChooserDescriptor.isChooseJars());
      invoke(chooser, "setCanChooseDirectories:", myChooserDescriptor.isChooseFolders());
      invoke(chooser, "setAllowsMultipleSelection:", myChooserDescriptor.isChooseMultiple());
      invoke(chooser, "setTreatsFilePackagesAsDirectories:", myChooserDescriptor.isChooseFolders());

      if (Foundation.isClassRespondsToSelector(nsOpenPanel, Foundation.createSelector("setCanCreateDirectories:"))) {
        invoke(chooser, "setCanCreateDirectories:", true);
      }
      else if (Foundation.isClassRespondsToSelector(nsOpenPanel, Foundation.createSelector("_setIncludeNewFolderButton:"))) {
        invoke(chooser, "_setIncludeNewFolderButton:", true);
      }

      final boolean showHidden = Boolean.TRUE.equals(myChooserDescriptor.getUserData(NATIVE_MAC_FILE_CHOOSER_SHOW_HIDDEN_FILES_ENABLED));
      if (showHidden || Registry.is("ide.mac.filechooser.showhidden.files")) {
        if (Foundation.isClassRespondsToSelector(nsOpenPanel, Foundation.createSelector("setShowsHiddenFiles:"))) {
          invoke(chooser, "setShowsHiddenFiles:", true);
        }
      }

      invoke(chooser, "setDelegate:", self);

      ID directory = null;
      ID file = null;
      final String toSelectPath = toSelect == null || toSelect.intValue() == 0 ? null : Foundation.toStringViaUTF8(toSelect);
      final VirtualFile toSelectFile = toSelectPath == null ? null : LocalFileSystem.getInstance().findFileByPath(toSelectPath);
      if (toSelectFile != null) {
        if (toSelectFile.isDirectory()) {
          directory = toSelect;
        }
        else {
          directory = Foundation.nsString(toSelectFile.getParent().getPath());
          file = Foundation.nsString(toSelectFile.getName());
        }
      }

      ID types = null;
      if (!myChooserDescriptor.isChooseFiles() && myChooserDescriptor.isChooseJars()) {
        types = invoke("NSArray", "arrayWithObject:", Foundation.nsString("jar"));
      }

      if (mySheetCallback != null) {
        final Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (activeWindow != null) {
          String activeWindowTitle = null;
          if (activeWindow instanceof Frame) {
            activeWindowTitle = ((Frame)activeWindow).getTitle();
          }
          else if (activeWindow instanceof JDialog) {
            activeWindowTitle = ((JDialog)activeWindow).getTitle();
          }

          final ID focusedWindow = MacUtil.findWindowForTitle(activeWindowTitle);
          if (focusedWindow != null) {
            invoke(chooser, "beginSheetForDirectory:file:types:modalForWindow:modalDelegate:didEndSelector:contextInfo:",
                   directory, file, types, focusedWindow, self, Foundation.createSelector("openPanelDidEnd:returnCode:contextInfo:"), null);
            
            if (directory != null) {
              Foundation.cfRelease(directory);
            }

            if (file != null) {
              Foundation.cfRelease(file);
            }
          }
        }
      }
      else {
        final ID result = invoke(chooser, "runModalForDirectory:file:types:", directory, file, types);
        processResult(result, chooser);
      }
    }
  };

  private static void processResult(final ID result, final ID panel) {
    //noinspection SSBasedInspection
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        if (myFakeDialog != null) {
          myFakeDialog.dispose();
          myFakeDialog = null;
        }
      }
    });

    final List<String> resultFiles = new ArrayList<String>();
    if (result != null && OK == result.intValue()) {
      ID fileNamesArray = invoke(panel, "filenames");
      ID enumerator = invoke(fileNamesArray, "objectEnumerator");

      while (true) {
        final ID filename = invoke(enumerator, "nextObject");
        if (filename == null || 0 == filename.intValue()) break;

        String s = Foundation.toStringViaUTF8(filename);
        if (s != null) {
          resultFiles.add(s);
        }
      }

      myResultPaths = resultFiles;
    }
  }

  static {
    final ID delegateClass = Foundation.allocateObjcClassPair(Foundation.getObjcClass("NSObject"), "NSOpenPanelDelegate_");
    if (!Foundation.addMethod(delegateClass, Foundation.createSelector("panel:shouldShowFilename:"), SHOULD_SHOW_FILENAME_CALLBACK, "B*")) {
      throw new RuntimeException("Unable to add method to objective-c delegate class!");
    }
    if (!Foundation.addMethod(delegateClass, Foundation.createSelector("panel:isValidFilename:"), IS_VALID_FILENAME_CALLBACK, "B*")) {
      throw new RuntimeException("Unable to add method to objective-c delegate class!");
    }
    if (!Foundation.addMethod(delegateClass, Foundation.createSelector("showOpenPanel:"), MAIN_THREAD_RUNNABLE, "v*")) {
      throw new RuntimeException("Unable to add method to objective-c delegate class!");
    }
    if (!Foundation.addMethod(delegateClass, Foundation.createSelector("openPanelDidEnd:returnCode:contextInfo:"), OPEN_PANEL_DID_END,
                              "v*i")) {
      throw new RuntimeException("Unable to add method to objective-c delegate class!");
    }
    if (!Foundation.addMethod(delegateClass, Foundation.createSelector("panel:shouldEnableURL:"), SHOULD_ENABLE_URL, "B@@")) {
      throw new RuntimeException("Unable to add method to objective-c delegate class!");
    }
    Foundation.registerObjcClassPair(delegateClass);
  }

  public MacFileChooserDialogImpl(@NotNull FileChooserDescriptor chooserDescriptor) {
    myChooserDescriptor = chooserDescriptor;
  }

  private static void showNativeChooser(@Nullable VirtualFile toSelect) {
    final ID autoReleasePool = createAutoReleasePool();

    try {
      final ID delegate = invoke(Foundation.getObjcClass("NSOpenPanelDelegate_"), "new");

      final ID select = toSelect == null ? null : Foundation.nsString(toSelect.getPath());
      Foundation.cfRetain(delegate);

      invoke(delegate, "performSelectorOnMainThread:withObject:waitUntilDone:", Foundation.createSelector("showOpenPanel:"), select, false);
    }
    finally {
      invoke(autoReleasePool, "release");
    }
  }

  private static void showNativeChooserAsSheet(@Nullable VirtualFile toSelect) {
    final ID autoReleasePool = createAutoReleasePool();
    try {
      final ID delegate = invoke(Foundation.getObjcClass("NSOpenPanelDelegate_"), "new");
      Foundation.cfRetain(delegate);

      final ID select = toSelect == null ? null : Foundation.nsString(toSelect.getPath());

      invoke(delegate, "performSelectorOnMainThread:withObject:waitUntilDone:", Foundation.createSelector("showOpenPanel:"), select, false);
    }
    finally {
      invoke(autoReleasePool, "release");
    }
  }

  public void chooseWithSheet(final VirtualFile toSelect, final Project project, @NotNull MacFileChooserCallback callback) {
    assert !myFileChooserActive : "Current native file chooser should finish before next usage!";

    mySheetCallback = callback;

    //noinspection SSBasedInspection
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        showNativeChooserAsSheet(getToSelect(toSelect, project));
      }
    });
  }

  @NotNull
  public VirtualFile[] choose(final VirtualFile toSelect, final Project project) {
    assert !myFileChooserActive : "Current native file chooser should finish before next usage!";
    myFileChooserActive = true;

    //noinspection SSBasedInspection
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        showNativeChooser(getToSelect(toSelect, project));
      }
    });

    final Window parent = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    if (parent instanceof Frame) {
      myFakeDialog = new JDialog((Frame)parent);
    }
    else if (parent instanceof JDialog) {
      myFakeDialog = new JDialog(((JDialog)parent));
    }
    else {
      myFakeDialog = new JDialog((JFrame)null);
    }

    myFakeDialog.setModal(true);
    myFakeDialog.setUndecorated(true);
    myFakeDialog.getRootPane().putClientProperty("Window.shadow", Boolean.FALSE);

    myFakeDialog.setSize(0, 0);
    myFakeDialog.setVisible(true);

    try {
      final String[] paths = myResultPaths != null ? ArrayUtil.toStringArray(myResultPaths) : ArrayUtil.EMPTY_STRING_ARRAY;
      return FileChooserUtil.getFiles(paths);
    }
    finally {
      myFileChooserActive = false;
      myResultPaths = null;
    }
  }

  private static VirtualFile getToSelect(VirtualFile toSelect, Project project) {
    final VirtualFile[] selectFile = new VirtualFile[]{null};
    if (toSelect == null) {
      if (project != null && project.getBaseDir() != null) {
        selectFile[0] = project.getBaseDir();
      }
    }
    else {
      selectFile[0] = toSelect.isValid() ? toSelect : null;
    }
    return selectFile[0];
  }

  private static ID createAutoReleasePool() {
    return invoke("NSAutoreleasePool", "new");
  }

  private static ID invoke(@NotNull final String className, @NotNull final String selector, Object... args) {
    return invoke(Foundation.getObjcClass(className), selector, args);
  }

  private static ID invoke(@NotNull final ID id, @NotNull final String selector, Object... args) {
    return Foundation.invoke(id, Foundation.createSelector(selector), args);
  }
}
