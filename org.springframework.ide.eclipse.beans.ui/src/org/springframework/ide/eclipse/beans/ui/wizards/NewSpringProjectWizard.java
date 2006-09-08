/*
 * Copyright 2002-2006 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ide.eclipse.beans.ui.wizards;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExecutableExtension;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.wizards.JavaCapabilityConfigurationPage;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.springframework.ide.eclipse.beans.core.internal.model.BeansProject;
import org.springframework.ide.eclipse.beans.ui.BeansUIImages;
import org.springframework.ide.eclipse.beans.ui.BeansUIPlugin;
import org.springframework.ide.eclipse.core.SpringCore;
import org.springframework.ide.eclipse.core.SpringCoreUtils;

/**
 * @author Torsten Juergeleit
 */
public class NewSpringProjectWizard extends Wizard
			implements INewWizard, IExecutableExtension  {

	private NewSpringProjectCreationPage mainPage;
	private IProject newProject;

	public NewSpringProjectWizard() {
		setDialogSettings(BeansUIPlugin.getDefault().getDialogSettings());
	}

	/**
	 * Returns the newly created project.
	 * 
	 * @return the created project, or <code>null</code> if project not
	 *         created
	 */
	public IProject getNewProject() {
		return newProject;
	}

	public void init(IWorkbench workbench, IStructuredSelection selection) {
		setDefaultPageImageDescriptor(BeansUIImages.DESC_WIZ_PROJECT);
		setWindowTitle(BeansWizardsMessages.NewProject_windowTitle);
		setDialogSettings(BeansUIPlugin.getDefault().getDialogSettings());
		setNeedsProgressMonitor(true);
	}

	public void addPages() {
		mainPage = new NewSpringProjectCreationPage("springNewProjectPage");
		mainPage.setTitle(BeansWizardsMessages.NewProject_title);
		mainPage.setDescription(BeansWizardsMessages.NewProject_description);
		addPage(mainPage);
	}

    /**
     * Stores the configuration element for the wizard. The config element will
     * be used in <code>performFinish</code> to set the result perspective.
     */
    public void setInitializationData(IConfigurationElement config,
            String propertyName, Object data) {
    }

	public boolean performFinish() {

		// get the data from the UI widgets
		final IProject project = mainPage.getProjectHandle();
		final Set configExtensions = mainPage.getConfigExtensions();
		final boolean isJavaProject = mainPage.isJavaProject();
		final String sourceDir = mainPage.getSourceDirectory().trim();
		final String outputDir = mainPage.getOutputDirectory().trim();

		// create project descriptor
		IPath newPath = null;
		if (!mainPage.useDefaults()) {
			newPath = mainPage.getLocationPath();
		}
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IProjectDescription description = workspace
				.newProjectDescription(project.getName());
		description.setLocation(newPath);

		// initialize a JCC page in UI thread
		final JavaCapabilityConfigurationPage jccp =
				new JavaCapabilityConfigurationPage();
		if (isJavaProject) {
			IJavaProject jproject = JavaCore.create(project);
			IPath source = (sourceDir.length() == 0 ? project
					.getFullPath() : project.getFolder(sourceDir)
					.getFullPath());
			IPath output = (outputDir.length() == 0 ? project
					.getFullPath() : project.getFolder(outputDir)
					.getFullPath());
			IClasspathEntry[] cpEntries = new IClasspathEntry[] {
					JavaCore.newSourceEntry(source) };
			jccp.init(jproject, output, cpEntries, true);
		}

		// create the new Spring project operation
		WorkspaceModifyOperation op = new WorkspaceModifyOperation() {
			protected void execute(IProgressMonitor monitor)
					throws CoreException {
				monitor.beginTask(
						BeansWizardsMessages.NewProject_createNewProject, 2000);
				createSpringProject(project, description, configExtensions,
						new SubProgressMonitor(monitor, 1000));
				if (isJavaProject) {

					// convert to java project
					try {
						jccp.configureJavaProject(new SubProgressMonitor(
								monitor, 1000));
					} catch (InterruptedException e) {
						throw new OperationCanceledException(e.getMessage());
					}
				}
				monitor.done();
			}
		};

		// run the new project creation operation
		try {
			getContainer().run(true, true, op);
		} catch (InterruptedException e) {
			return false;
		} catch (InvocationTargetException e) {

			// ie.- one of the steps resulted in a core exception
			Throwable t = e.getTargetException();
			if (t instanceof CoreException) {
				if (((CoreException) t).getStatus().getCode() ==
							IResourceStatus.CASE_VARIANT_EXISTS) {
					MessageDialog.openError(getShell(),
								BeansWizardsMessages.NewProject_errorMessage,
								NLS.bind(BeansWizardsMessages.
										NewProject_caseVariantExistsError,
													project.getName()));
				} else {
					ErrorDialog.openError(getShell(),
							BeansWizardsMessages.NewProject_errorMessage,
							null, // no special message
							((CoreException) t).getStatus());
				}
			} else {
				// CoreExceptions are handled above, but unexpected runtime
				// exceptions and errors may still occur.
				BeansUIPlugin.getDefault().getLog().log(new Status(
						IStatus.ERROR, BeansUIPlugin.PLUGIN_ID, 0,
						t.toString(), t));
				MessageDialog.openError(getShell(),
						BeansWizardsMessages.NewProject_errorMessage,
						NLS.bind(BeansWizardsMessages.NewProject_internalError,
								t.getMessage()));
			}
			return false;
		}
		newProject = project;
		return true;
	}

	/**
	 * Creates a project resource given the project handle and description.
	 * 
	 * @param projectHandle
	 *            the project handle to create a project resource for
	 * @param description
	 *            the project description to create a project resource for
	 * @param monitor
	 *            the progress monitor to show visual progress with
	 * 
	 * @exception CoreException
	 *                if the operation fails
	 * @exception OperationCanceledException
	 *                if the operation is canceled
	 */
	protected void createSpringProject(IProject projectHandle,
				IProjectDescription description, Set configExtensions,
				IProgressMonitor monitor) throws CoreException,
						OperationCanceledException {
		try {
			monitor.beginTask(BeansWizardsMessages.NewProject_createProject, 8);

			projectHandle.create(description, monitor);
			monitor.worked(2);
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}

			projectHandle.open(IResource.BACKGROUND_REFRESH, monitor);
			monitor.worked(2);
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}

			SpringCoreUtils.addProjectNature(projectHandle,
					SpringCore.NATURE_ID, monitor);
			monitor.worked(2);
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}

			BeansProject project = new BeansProject(projectHandle);
			project.setConfigExtensions(configExtensions);
			project.saveDescription();
			monitor.worked(2);
		} finally {
			monitor.done();
		}
	}
}
