package com.xpe.mobile;

import android.app.AlertDialog;
import android.widget.EditText;
import com.xpe.mobile.project.ProjectBrowserDialog;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.project.ProjectRecord;
import java.io.IOException;
import java.util.UUID;

/** MainProjectActions responsibilities extracted from MainActivity. */
final class MainProjectActions {
    private final MainActivity delegateOwner;

    MainProjectActions(MainActivity delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void showProjectLibrary(boolean startup) {
        delegateOwner.mainProjectSession.runProjectTask(R.string.project_library_loading,
                delegateOwner.projectLibrary::discoverWorkspaces,
                discovery -> delegateOwner.projectBrowser.show(discovery, startup,
                        new ProjectBrowserDialog.Callback() {
                            @Override
                            public void onCreateRequested() {
                                delegateOwner.newProjectDialog.showCreateProjectDialog();
                            }

                            @Override
                            public void onImportRequested() {
                                delegateOwner.mainFileActions.requestPackageOpen();
                            }

                            @Override
                            public void onOpen(ProjectRecord project) {
                                delegateOwner.mainProjectActions.openLibraryProject(project, true, false);
                            }

                            @Override
                            public void onRename(ProjectRecord project) {
                                delegateOwner.mainProjectActions.showRenameProject(project);
                            }

                            @Override
                            public void onDuplicate(ProjectRecord project) {
                                delegateOwner.mainProjectActions.duplicateProject(project);
                            }

                            @Override
                            public void onExport(ProjectRecord project) {
                                delegateOwner.mainProjectActions.openLibraryProject(project, false, true);
                            }

                            @Override
                            public void onRemove(ProjectRecord project) {
                                delegateOwner.mainProjectActions.confirmRemoveProject(project);
                            }

                            @Override
                            public void onIssueAction(ProjectLibrary.WorkspaceIssue issue) {
                                delegateOwner.mainProjectActions.handleWorkspaceIssue(issue);
                            }
                        }));
    }

    void confirmRemoveProject(ProjectRecord project) {
        delegateOwner.mainProjectActions.confirmRemoveIndexedProject(project.getId(), project.getName());
    }

    void confirmRemoveIndexedProject(String projectId, String projectName) {
        new AlertDialog.Builder(delegateOwner)
                .setTitle(R.string.project_remove_title)
                .setMessage(delegateOwner.getString(R.string.project_remove_message, projectName))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.project_action_remove, (dialog, which) -> {
                    boolean active = projectId.equals(delegateOwner.currentProjectId);
                    delegateOwner.mainProjectSession.runProjectTask(R.string.project_removing,
                            () -> {
                                InitialContent fallback = active
                                        ? delegateOwner.mainProjectSession.readFallbackContent(null, false) : null;
                                delegateOwner.projectLibrary.removeProject(projectId);
                                return fallback;
                            }, fallback -> {
                        if (active) {
                            delegateOwner.currentPackage = null;
                            delegateOwner.audioController.setHitSoundPackage(null);
                            delegateOwner.currentPackageSourceUri = null;
                            delegateOwner.currentProjectId = null;
                            delegateOwner.currentProjectName = null;
                            delegateOwner.mainPreviewResources.releaseAudio();
                            delegateOwner.mainProjectSession.activateFallbackContent(fallback);
                        }
                        delegateOwner.showMessage(delegateOwner.getString(R.string.project_removed, projectName));
                        delegateOwner.mainProjectActions.showProjectLibrary(false);
                    });
                })
                .show();
    }

    void openLibraryProject(ProjectRecord project, boolean notify, boolean exportAfterOpen) {
        try {
            delegateOwner.mainProjectSession.saveCurrentProject();
        } catch (IOException exception) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_library_error, MainActivity.safeMessage(exception)));
            return;
        }
        delegateOwner.mainProjectSession.runProjectTask(R.string.project_opening,
                () -> delegateOwner.projectLibrary.openProject(project.getId(), System.currentTimeMillis()),
                opened -> {
                    delegateOwner.mainProjectSession.activatePackage(opened.getChartPackage(), project.getId(), null,
                            opened.getRecord().getName());
                    if (notify) delegateOwner.showMessage(delegateOwner.getString(
                            R.string.project_opened, opened.getRecord().getName()));
                    if (exportAfterOpen) delegateOwner.mainFileActions.requestPackageSave();
                });
    }

    void showRenameProject(ProjectRecord project) {
        EditText input = new EditText(delegateOwner);
        input.setText(project.getName());
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(delegateOwner)
                .setTitle(R.string.project_rename_title)
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.project_action_rename, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ProjectLibrary.State state = delegateOwner.projectLibrary.renameProject(
                                project.getId(), input.getText().toString());
                        ProjectRecord renamed = state.find(project.getId());
                        if (project.getId().equals(delegateOwner.currentProjectId) && renamed != null) {
                            delegateOwner.currentProjectName = renamed.getName();
                            delegateOwner.editorView.setProjectName(delegateOwner.currentProjectName);
                        }
                        dialog.dismiss();
                        delegateOwner.showMessage(delegateOwner.getString(R.string.project_renamed,
                                renamed == null ? input.getText().toString().trim() : renamed.getName()));
                        delegateOwner.mainProjectActions.showProjectLibrary(false);
                    } catch (IOException exception) {
                        delegateOwner.showMessage(delegateOwner.getString(R.string.project_library_error, MainActivity.safeMessage(exception)));
                    }
                }));
        dialog.show();
    }

    void duplicateProject(ProjectRecord project) {
        try {
            delegateOwner.mainProjectSession.saveCurrentProject();
        } catch (IOException exception) {
            delegateOwner.showMessage(delegateOwner.getString(R.string.project_library_error, MainActivity.safeMessage(exception)));
            return;
        }
        String newId = UUID.randomUUID().toString();
        delegateOwner.mainProjectSession.runProjectTask(R.string.project_duplicating,
                () -> delegateOwner.projectLibrary.duplicateProject(
                        project.getId(), newId, System.currentTimeMillis()), duplicated -> {
                    delegateOwner.mainProjectSession.activatePackage(duplicated.getChartPackage(), newId, null,
                            duplicated.getRecord().getName());
                    delegateOwner.showMessage(delegateOwner.getString(R.string.project_duplicated,
                            duplicated.getRecord().getName()));
                });
    }

    void handleWorkspaceIssue(ProjectLibrary.WorkspaceIssue issue) {
        if (issue.isIndexed()) {
            delegateOwner.mainProjectActions.confirmRemoveIndexedProject(issue.getProjectId(), issue.getDisplayName());
        } else if (issue.getStatus() == ProjectLibrary.WorkspaceStatus.RECOVERABLE) {
            delegateOwner.mainProjectSession.runProjectTask(R.string.project_recovering,
                    () -> delegateOwner.projectLibrary.recoverOrphan(
                            issue.getProjectId(), System.currentTimeMillis()), recovered -> {
                        delegateOwner.mainProjectSession.activatePackage(recovered.getChartPackage(), recovered.getRecord().getId(),
                                null, recovered.getRecord().getName());
                        delegateOwner.showMessage(delegateOwner.getString(R.string.project_recovered,
                                recovered.getRecord().getName()));
                    });
        } else {
            new AlertDialog.Builder(delegateOwner)
                    .setTitle(R.string.project_recovery_remove_title)
                    .setMessage(delegateOwner.getString(R.string.project_recovery_remove_message,
                            issue.getDisplayName(), issue.getMessage()))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.project_recovery_remove, (dialog, which) ->
                            delegateOwner.mainProjectSession.runProjectTask(R.string.project_removing,
                                    () -> delegateOwner.projectLibrary.removeUnindexedWorkspace(
                                            issue.getProjectId()), state -> {
                                        delegateOwner.showMessage(delegateOwner.getString(R.string.project_recovery_removed,
                                                issue.getDisplayName()));
                                        delegateOwner.mainProjectActions.showProjectLibrary(false);
                                    }))
                    .show();
        }
    }
}
