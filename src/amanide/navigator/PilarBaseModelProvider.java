package amanide.navigator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.model.BaseWorkbenchContentProvider;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;

import amanide.AmanIDEPlugin;
import amanide.callbacks.ICallback;
import amanide.editors.codecompletion.PilarPathHelper;
import amanide.natures.IPilarNature;
import amanide.natures.IPilarNatureListener;
import amanide.natures.PilarNature;
import amanide.natures.PilarNatureListenersManager;
import amanide.navigator.elements.IWrappedResource;
import amanide.navigator.elements.PilarFile;
import amanide.navigator.elements.PilarFolder;
import amanide.navigator.elements.PilarProjectSourceFolder;
import amanide.navigator.elements.PilarResource;
import amanide.navigator.elements.PilarSourceFolder;
import amanide.structure.TreeNode;
import amanide.utils.Log;
import amanide.utils.StringUtils;

/**
 * A good part of the refresh for the model was gotten from
 * org.eclipse.ui.model.WorkbenchContentProvider (mostly just changed the way to
 * get content changes in pilar files)
 *
 * There are other important notifications that we need to learn about. Namely:
 * - When a source folder is created - When the way to see it changes (flat or
 * not)
 *
 * adapted from org.pilar.pydev.navigator.PilarBaseModelProvider.java
 * 
 * @author Fengguo Wei
 */
public abstract class PilarBaseModelProvider extends
		BaseWorkbenchContentProvider implements IResourceChangeListener,
		IPilarNatureListener, IPropertyChangeListener {

	/**
	 * Object representing an empty array.
	 */
	private static final Object[] EMPTY = new Object[0];

	/**
	 * Type of the error markers to show in the amanide package explorer.
	 */
	public static final String AMANIDE_PACKAGE_EXPORER_PROBLEM_MARKER = "amanide.AmanIDEProjectErrorMarkers";

	/**
	 * These are the source folders that can be found in this file provider. The
	 * way we see things in this provider, the pilar model starts only after
	 * some source folder is found.
	 */
	private Map<IProject, ProjectInfoForPackageExplorer> projectToSourceFolders = new HashMap<IProject, ProjectInfoForPackageExplorer>();

	/**
	 * This is the viewer that we're using to see the contents of this file
	 * provider.
	 */
	protected CommonViewer viewer;

	/**
	 * This is the helper we have to know if the top-level elements should be
	 * working sets or only projects.
	 */
	protected final TopLevelProjectsOrWorkingSetChoice topLevelChoice;

	private ICommonContentExtensionSite aConfig;

	private IWorkspace[] input;

	public static final boolean DEBUG = false;

	/**
	 * This callback should return the working sets available.
	 */
	protected static ICallback<List<IWorkingSet>, IWorkspaceRoot> getWorkingSetsCallback = new ICallback<List<IWorkingSet>, IWorkspaceRoot>() {
		@Override
		public List<IWorkingSet> call(IWorkspaceRoot arg) {
			return Arrays.asList(PlatformUI.getWorkbench()
					.getWorkingSetManager().getWorkingSets());
		}
	};

	/**
	 * Constructor... registers itself as a pilar nature listener
	 */
	public PilarBaseModelProvider() {
		PilarNatureListenersManager.addPilarNatureListener(this);
		AmanIDEPlugin plugin = AmanIDEPlugin.getDefault();
		IPreferenceStore preferenceStore = plugin.getPreferenceStore();
		preferenceStore.addPropertyChangeListener(this);

		// just leave it created
		topLevelChoice = new TopLevelProjectsOrWorkingSetChoice();
	}

	/**
	 * Initializes the viewer and the choice for top-level elements.
	 */
	public void init(ICommonContentExtensionSite aConfig) {
		this.aConfig = aConfig;
	}

	/**
	 * Helper to provide a single update with multiple notifications.
	 */
	private class Updater extends Job {

		/**
		 * The pilarpath set for the project
		 */
		private List<String> projectPilarpath;

		/**
		 * The project which had the pilarpath rebuilt
		 */
		private IProject project;

		/**
		 * Lock for accessing project and projectPilarpath
		 */
		private Object updaterLock = new Object();

		public Updater() {
			super("Model provider updating pilarpath");
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			IProject projectToUse;
			List<String> projectPilarpathToUse;
			synchronized (updaterLock) {
				projectToUse = project;
				projectPilarpathToUse = projectPilarpath;

				// Clear the fields (we already have the locals with the values
				// we need.)
				project = null;
				projectPilarpath = null;
			}

			// No need to be synchronized (that's the slow part)
			if (projectToUse != null && projectPilarpathToUse != null) {
				internalDoNotifyPilarPathRebuilt(projectToUse,
						projectPilarpathToUse);
			}

			return Status.OK_STATUS;
		}

		/**
		 * Sets the needed parameters to rebuild the pilarpath.
		 */
		public void setNeededParameters(IProject project,
				List<String> projectPilarpath) {
			synchronized (updaterLock) {
				this.project = project;
				this.projectPilarpath = projectPilarpath;
			}
		}
	}

	/**
	 * We need to have one updater per project. After created, it'll remain
	 * always there.
	 */
	private static Map<IProject, Updater> projectToUpdater = new HashMap<IProject, Updater>();
	private static Object projectToUpdaterLock = new Object();

	private Updater getUpdater(IProject project) {
		synchronized (projectToUpdaterLock) {
			Updater updater = projectToUpdater.get(project);
			if (updater == null) {
				updater = new Updater();
				projectToUpdater.put(project, updater);
			}
			return updater;
		}
	}

	/**
	 * Helper so that we can have many notifications and create a single
	 * request.
	 * 
	 * @param projectPilarpath
	 * @param project
	 */
	private void createAndStartUpdater(IProject project,
			List<String> projectPilarpath) {
		Updater updater = getUpdater(project);
		updater.setNeededParameters(project, projectPilarpath);
		updater.schedule(200);
	}

	/**
	 * Notification received when the pilarpath has been changed or rebuilt.
	 */
	@Override
	public void notifyPilarPathRebuilt(IProject project, IPilarNature nature) {
		if (project == null) {
			return;
		}

		List<String> projectPilarpath;
		if (nature == null) {
			// the nature has just been removed.
			projectPilarpath = new ArrayList<String>();
		} else {
			try {
				String source = nature.getPilarPathNature()
						.getOnlyProjectPilarPathStr();
				projectPilarpath = StringUtils.splitAndRemoveEmptyTrimmed(
						source, '|');
			} catch (CoreException e) {
				projectPilarpath = new ArrayList<String>();
			}
		}

		createAndStartUpdater(project, projectPilarpath);
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		// When a property that'd change an icon changes, the tree must be
		// updated.
		// String property = event.getProperty();
		// if (PyTitlePreferencesPage
		// .isTitlePreferencesIconRelatedProperty(property)) {
		// IWorkspace[] localInput = this.input;
		// if (localInput != null) {
		// for (IWorkspace iWorkspace : localInput) {
		// IWorkspaceRoot root = iWorkspace.getRoot();
		// if (root != null) {
		// // Update all children too (getUpdateRunnable wouldn't
		// // update children)
		// Runnable runnable = getRefreshRunnable(root);
		//
		// final Collection<Runnable> runnables = new ArrayList<Runnable>();
		// runnables.add(runnable);
		// processRunnables(runnables);
		// }
		// }
		// }
		//
		// }
	}

	/**
	 * This is the actual implementation of the rebuild.
	 *
	 * @return the element that should be refreshed or null if the project
	 *         location can't be determined!
	 */
	/* default */IResource internalDoNotifyPilarPathRebuilt(IProject project,
			List<String> projectPilarpath) {
		IResource refreshObject = project;
		IPath location = project.getLocation();
		if (location == null) {
			return null;
		}

		if (DEBUG) {
			Log.log("Rebuilding pilarpath: " + project + " - "
					+ projectPilarpath);
		}
		HashSet<Path> projectPilarpathSet = new HashSet<Path>();

		for (String string : projectPilarpath) {
			Path newPath = new Path(string);
			if (location.equals(newPath)) {
				refreshObject = project.getParent();
			}
			projectPilarpathSet.add(newPath);
		}

		ProjectInfoForPackageExplorer projectInfo = getProjectInfo(project);
		if (projectInfo != null) {
			projectInfo.recreateInfo(project);

			Set<PilarSourceFolder> existingSourceFolders = projectInfo.sourceFolders;
			Log.log("existingSourceFolders:" + existingSourceFolders);
			if (existingSourceFolders != null) {
				// iterate in a copy
				for (PilarSourceFolder pilarSourceFolder : new HashSet<PilarSourceFolder>(
						existingSourceFolders)) {
					IPath fullPath = pilarSourceFolder.container.getLocation();
					Log.log("fullPath:" + fullPath);
					if (!projectPilarpathSet.contains(fullPath)) {
						if (pilarSourceFolder instanceof PilarProjectSourceFolder) {
							refreshObject = project.getParent();
						}
						existingSourceFolders.remove(pilarSourceFolder);
						if (DEBUG) {
							Log.log("Removing:" + pilarSourceFolder + " - "
									+ fullPath);
						}
					}
				}
			}
		}
		Log.log("refreshObject:" + refreshObject);
		Runnable refreshRunnable = getRefreshRunnable(refreshObject);
		final Collection<Runnable> runnables = new ArrayList<Runnable>();
		runnables.add(refreshRunnable);
		processRunnables(runnables);
		return refreshObject;
	}

	/**
	 * @return the information on a project. Can create it if it's not
	 *         available.
	 */
	protected synchronized ProjectInfoForPackageExplorer getProjectInfo(
			final IProject project) {
		if (project == null) {
			return null;
		}
		Map<IProject, ProjectInfoForPackageExplorer> p = projectToSourceFolders;
		if (p != null) {
			ProjectInfoForPackageExplorer projectInfo = p.get(project);
			if (projectInfo == null) {
				if (!project.isOpen()) {
					return null;
				}
				// No project info: create it
				projectInfo = p.get(project);
				if (projectInfo == null) {
					projectInfo = new ProjectInfoForPackageExplorer(project);
					p.put(project, projectInfo);
				}
			} else {
				if (!project.isOpen()) {
					p.remove(project);
					projectInfo = null;
				}
			}
			Log.log("projectInfo:" + projectInfo);
			return projectInfo;
		}
		return null;
	}

	/**
	 * @see PilarModelProvider#getResourceInPilarModel(IResource, boolean,
	 *      boolean)
	 */
	protected Object getResourceInPilarModel(IResource object) {
		return getResourceInPilarModel(object, false, false);
	}

	/**
	 * @see PilarModelProvider#getResourceInPilarModel(IResource, boolean,
	 *      boolean)
	 */
	protected Object getResourceInPilarModel(IResource object,
			boolean returnNullIfNotFound) {
		return getResourceInPilarModel(object, false, returnNullIfNotFound);
	}

	/**
	 * Given some IResource in the file system, return the representation for it
	 * in the pilar model or the resource itself if it could not be found in the
	 * pilar model.
	 *
	 * Note that this method only returns some resource already created (it does
	 * not create some resource if it still does not exist)
	 */
	protected Object getResourceInPilarModel(IResource object,
			boolean removeFoundResource, boolean returnNullIfNotFound) {
		// if (DEBUG) {
		System.out.println("Getting resource in pilar model:" + object);
		// }
		Set<PilarSourceFolder> sourceFolders = getProjectSourceFolders(object
				.getProject());
		System.out.println("Getting resource in pilar model parent:"
				+ object.getProject());
		System.out.println("sourceFolders:" + sourceFolders);
		Object f = null;
		PilarSourceFolder sourceFolder = null;
		for (Iterator<PilarSourceFolder> it = sourceFolders.iterator(); f == null
				&& it.hasNext();) {
			sourceFolder = it.next();
			if (sourceFolder.getActualObject().equals(object)) {
				f = sourceFolder;
			} else {
				f = sourceFolder.getChild(object);
			}
		}
		if (f == null) {
			if (returnNullIfNotFound) {
				return null;
			} else {
				return object;
			}
		} else {
			if (removeFoundResource) {
				if (f == sourceFolder) {
					sourceFolders.remove(f);
				} else {
					sourceFolder.removeChild(object);
				}
			}
		}
		return f;
	}

	/**
	 * @param object
	 *            : the resource we're interested in
	 * @return a set with the PilarSourceFolder that exist in the project that
	 *         contains it
	 */
	protected Set<PilarSourceFolder> getProjectSourceFolders(IProject project) {
		ProjectInfoForPackageExplorer projectInfo = getProjectInfo(project);
		if (projectInfo != null) {
			return projectInfo.sourceFolders;
		}
		return new HashSet<PilarSourceFolder>();
	}

	/**
	 * @return the parent for some element.
	 */
	@Override
	public Object getParent(Object element) {
		if (DEBUG) {
			Log.log("getParent for: " + element);
		}

		Object parent = null;
		// Now, we got the parent for the resources correctly at this point, but
		// there's one last thing we may need to
		// do: the actual parent may be a working set!
		Object p = this.topLevelChoice.getWorkingSetParentIfAvailable(element,
				getWorkingSetsCallback);
		if (p != null) {
			parent = p;

		} else if (element instanceof IWrappedResource) {
			// just return the parent
			IWrappedResource resource = (IWrappedResource) element;
			parent = resource.getParentElement();

		} else if (element instanceof IWorkingSet) {
			parent = ResourcesPlugin.getWorkspace().getRoot();

		} else if (element instanceof TreeNode) {
			TreeNode treeNode = (TreeNode) element;
			return treeNode.getParent();
		}

		if (parent == null) {
			parent = super.getParent(element);
		}
		if (DEBUG) {
			Log.log("getParent RETURN: " + parent);
		}
		return parent;
	}

	/**
	 * @return whether there are children for the given element. Note that there
	 *         is an optimization in this method, so that it works correctly for
	 *         elements that are not pilar files, and returns true if it is a
	 *         pilar file with any content (even if that content does not
	 *         actually map to a node.
	 *
	 * @see org.eclipse.ui.model.BaseWorkbenchContentProvider#hasChildren(java.lang.Object)
	 */
	@Override
	public boolean hasChildren(Object element) {
		if (element instanceof PilarFile) {
			// If we're not showing nodes, return false.
			// INavigatorContentService contentService = viewer
			// .getNavigatorContentService();
			// INavigatorFilterService filterService = contentService
			// .getFilterService();
			// ViewerFilter[] visibleFilters = filterService
			// .getVisibleFilters(true);
			// for (ViewerFilter viewerFilter : visibleFilters) {
			// if (viewerFilter instanceof PilarNodeFilter) {
			// return false;
			// }
			// }

			// PilarFile f = (PilarFile) element;
			// if (PilarPathHelper.isValidSourceFile(f.getActualObject())) {
			// try {
			// InputStream contents = f.getContents();
			// try {
			// if (contents.read() == -1) {
			// return false; // if there is no content in the file,
			// // it has no children
			// } else {
			// return true; // if it has any content, it has
			// // children (performance reasons)
			// }
			// } finally {
			// contents.close();
			// }
			// } catch (Exception e) {
			// Log.log("Handled error getting contents.", e);
			// return false;
			// }
			// }
			return false;
		}
		if (element instanceof TreeNode<?>) {
			TreeNode<?> treeNode = (TreeNode<?>) element;
			return treeNode.hasChildren();
		}
		return getChildren(element).length > 0;
	}

	/**
	 * The inputs for this method are:
	 *
	 * IWorkingSet (in which case it will return the projects -- IResource --
	 * that are a part of the working set) IResource (in which case it will
	 * return IWrappedResource or IResources) IWrappedResource (in which case it
	 * will return IWrappedResources)
	 *
	 * @return the children for some element (IWrappedResource or IResource)
	 */
	@Override
	public Object[] getChildren(Object parentElement) {
		Object[] childrenToReturn = null;
		if (parentElement instanceof IWrappedResource) {
			// we're below some pilar model
			childrenToReturn = getChildrenForIWrappedResource((IWrappedResource) parentElement);

		} else if (parentElement instanceof IResource) {
			// now, this happens if we're not below a pilar model(so, we may
			// only find a source folder here)
			childrenToReturn = getChildrenForIResourceOrWorkingSet(parentElement);

		} else if (parentElement instanceof IWorkspaceRoot) {
			switch (topLevelChoice.getRootMode()) {
			case TopLevelProjectsOrWorkingSetChoice.WORKING_SETS:
				return PlatformUI.getWorkbench().getWorkingSetManager()
						.getWorkingSets();
			case TopLevelProjectsOrWorkingSetChoice.PROJECTS:
				// Just go on...
			}

		} else if (parentElement instanceof IWorkingSet) {
			if (parentElement instanceof IWorkingSet) {
				IWorkingSet workingSet = (IWorkingSet) parentElement;
				childrenToReturn = workingSet.getElements();
			}

		} else if (parentElement instanceof TreeNode<?>) {
			TreeNode<?> treeNode = (TreeNode<?>) parentElement;
			return treeNode.getChildren().toArray();
		}

		if (childrenToReturn == null) {
			childrenToReturn = EMPTY;
		}
		if (DEBUG) {
			for (Object c : childrenToReturn)
				Log.log("childrenToReturn: " + c);
		}
		return childrenToReturn;
	}

	/**
	 * @param parentElement
	 *            an IResource from where we want to get the children (or a
	 *            working set)
	 *
	 * @return as we're not below a source folder here, we have still not
	 *         entered the 'pilar' domain, and as the starting point for the
	 *         'pilar' domain is always a source folder, the things that can be
	 *         returned are IResources and PilarSourceFolders.
	 */
	private Object[] getChildrenForIResourceOrWorkingSet(Object parentElement) {
		PilarNature nature = null;
		IProject project = null;
		if (parentElement instanceof IResource) {
			project = ((IResource) parentElement).getProject();
		}

		// we can only get the nature if the project is open
		if (project != null && project.isOpen()) {
			nature = PilarNature.getPilarNature(project);
		}

		// replace folders -> source folders (we should only get here on a path
		// that's not below a source folder)
		Object[] childrenToReturn = super.getChildren(parentElement);
		for (Object cr : childrenToReturn)
			Log.log("childrenToReturn:   :" + cr);
		// if we don't have a pilar nature in this project, there is no way we
		// can have a PilarSourceFolder
		List<Object> ret = new ArrayList<Object>(childrenToReturn.length);
		for (int i = 0; i < childrenToReturn.length; i++) {
			PilarNature localNature = nature;
			IProject localProject = project;

			// now, first we have to try to get it (because it might already be
			// created)
			Object child = childrenToReturn[i];

			if (child == null) {
				continue;
			}

			// only add it if it wasn't null
			ret.add(child);
			System.out.println("child:::" + child);
			if (!(child instanceof IResource)) {
				// not an element that we can treat in pydev (but still, it was
				// already added)
				continue;
			}
			child = getResourceInPilarModel((IResource) child);
			System.out.println("child after:::" + child);
			if (child == null) {
				// ok, it was not in the pilar model (but it was already added
				// with the original representation, so, that's ok)
				continue;
			} else {
				ret.set(ret.size() - 1, child); // replace the element added for
												// the one in the pilar model
			}

			// if it is a folder (that is not already a PilarSourceFolder, it
			// might be that we have to create a PilarSourceFolder)
			if (child instanceof IContainer
					&& !(child instanceof PilarSourceFolder)) {
				IContainer container = (IContainer) child;

				try {
					// check if it is a source folder (and if it is, create it)
					if (localNature == null) {
						if (container instanceof IProject) {
							localProject = (IProject) container;
							if (localProject.isOpen() == false) {
								continue;
							} else {
								localNature = PilarNature
										.getPilarNature(localProject);
							}
						} else {
							continue;
						}
					}
					// if it's a pilar project, the nature can't be null
					if (localNature == null) {
						continue;
					}

					Set<String> sourcePathSet = localNature
							.getPilarPathNature().getProjectSourcePathSet(true);
					Log.log("sourcePathSet:  :" + sourcePathSet);
					IPath fullPath = container.getFullPath();
					Log.log("fullPath:  :" + fullPath);
					if (sourcePathSet.contains(fullPath.toString())) {
						PilarSourceFolder createdSourceFolder;
						if (container instanceof IFolder) {
							createdSourceFolder = new PilarSourceFolder(
									parentElement, (IFolder) container);
						} else if (container instanceof IProject) {
							createdSourceFolder = new PilarProjectSourceFolder(
									parentElement, (IProject) container);
						} else {
							throw new RuntimeException("Should not get here.");
						}
						ret.set(ret.size() - 1, createdSourceFolder); // replace
																		// the
																		// element
																		// added
																		// for
																		// the
																		// one
																		// in
																		// the
																		// pilar
																		// model
						Set<PilarSourceFolder> sourceFolders = getProjectSourceFolders(localProject);
						sourceFolders.add(createdSourceFolder);
					}
				} catch (CoreException e) {
					throw new RuntimeException(e);
				}
			}
		}
		Log.log("ret:  :" + ret);
		return ret.toArray();
	}

	/**
	 * @param wrappedResourceParent
	 *            : this is the parent that is an IWrappedResource (which means
	 *            that children will also be IWrappedResources)
	 *
	 * @return the children (an array of IWrappedResources)
	 */
	private Object[] getChildrenForIWrappedResource(
			IWrappedResource wrappedResourceParent) {
		// -------------------------------------------------------------------
		// get pilar nature
		PilarNature nature = null;
		Object[] childrenToReturn = null;
		Object obj = wrappedResourceParent.getActualObject();
		IProject project = null;
		if (obj instanceof IResource) {
			IResource resource = (IResource) obj;
			project = resource.getProject();
			if (project != null && project.isOpen()) {
				nature = PilarNature.getPilarNature(project);
			}
		}

		// -------------------------------------------------------------------
		if (wrappedResourceParent instanceof PilarFile) {
			// if it's a file, we want to show the classes and methods
			// PilarFile file = (PilarFile) wrappedResourceParent;
			// if (PilarPathHelper.isValidSourceFile(file.getActualObject())) {
			//
			// if (nature != null) {
			// ICodeCompletionASTManager astManager = nature
			// .getAstManager();
			// // the nature may still not be completely restored...
			// if (astManager != null) {
			// IModulesManager modulesManager = astManager
			// .getModulesManager();
			//
			// if (modulesManager instanceof IProjectModulesManager) {
			// IProjectModulesManager projectModulesManager =
			// (IProjectModulesManager) modulesManager;
			// String moduleName = projectModulesManager
			// .resolveModuleInDirectManager(file
			// .getActualObject());
			// if (moduleName != null) {
			// IModule module = projectModulesManager
			// .getModuleInDirectManager(moduleName,
			// nature, true);
			// if (module == null) {
			// // ok, something strange happened... it
			// // shouldn't be null... maybe empty, but not
			// // null at this point
			// // so, if it exists, let's try to create
			// // it...
			// // TODO: This should be moved to somewhere
			// // else.
			// String resourceOSString = AmanIDEPlugin
			// .getIResourceOSString(file
			// .getActualObject());
			// if (resourceOSString != null) {
			// File f = new File(resourceOSString);
			// if (f.exists()) {
			// projectModulesManager
			// .addModule(new ModulesKey(
			// moduleName, f));
			// module = projectModulesManager
			// .getModuleInDirectManager(
			// moduleName, nature,
			// true);
			// }
			// }
			// }
			// if (module instanceof SourceModule) {
			// SourceModule sourceModule = (SourceModule) module;
			//
			// OutlineCreatorVisitor visitor = OutlineCreatorVisitor
			// .create(sourceModule.getAst());
			// ParsedItem root = new ParsedItem(
			// visitor.getAll()
			// .toArray(
			// new ASTEntryWithChildren[0]),
			// null);
			// childrenToReturn = getChildrenFromParsedItem(
			// wrappedResourceParent, root, file);
			// }
			// }
			// }
			// }
			// }
			// }
		}

		// ------------------------------------------------------------- treat
		// folders and others
		else {
			Object[] children = super.getChildren(wrappedResourceParent
					.getActualObject());
			childrenToReturn = wrapChildren(wrappedResourceParent,
					wrappedResourceParent.getSourceFolder(), children);
		}
		return childrenToReturn;
	}

	/**
	 * This method changes the contents of the children so that the actual types
	 * are mapped to elements of our pilar model.
	 *
	 * @param parent
	 *            the parent (from the pilar model)
	 * @param pilarSourceFolder
	 *            this is the source folder that contains this resource
	 * @param children
	 *            these are the children thot should be wrapped (note that this
	 *            array is not actually changed -- a new array is created and
	 *            returned).
	 *
	 * @return an array with the wrapped types
	 */
	protected Object[] wrapChildren(IWrappedResource parent,
			PilarSourceFolder pilarSourceFolder, Object[] children) {
		List<Object> ret = new ArrayList<Object>(children.length);

		for (int i = 0; i < children.length; i++) {
			Object object = children[i];

			if (object instanceof IResource) {
				Object existing = getResourceInPilarModel((IResource) object,
						true);
				if (existing == null) {

					if (object instanceof IFolder) {
						object = new PilarFolder(parent, ((IFolder) object),
								pilarSourceFolder);

					} else if (object instanceof IFile) {
						object = new PilarFile(parent, ((IFile) object),
								pilarSourceFolder);

					} else if (object instanceof IResource) {
						object = new PilarResource(parent, (IResource) object,
								pilarSourceFolder);
					}
				} else { // existing != null
					object = existing;
				}
			}

			if (object == null) {
				continue;
			} else {
				ret.add(object);
			}

		}
		return ret.toArray();
	}

	/**
	 * @param parentElement
	 *            this is the elements returned
	 * @param root
	 *            this is the parsed item that has children that we want to
	 *            return
	 * @return the children elements (PilarNode) for the passed parsed item
	 */
	// private Object[] getChildrenFromParsedItem(Object parentElement,
	// ParsedItem root, PilarFile pilarFile) {
	// IParsedItem[] children = root.getChildren();
	//
	// PilarNode p[] = new PilarNode[children.length];
	// int i = 0;
	// // in this case, we just want to return the roots
	// for (IParsedItem e : children) {
	// p[i] = new PilarNode(pilarFile, parentElement, (ParsedItem) e);
	// i++;
	// }
	// return p;
	// }

	/*
	 * (non-Javadoc) Method declared on IContentProvider.
	 */
	@Override
	public void dispose() {
		try {
			this.projectToSourceFolders = null;
			if (viewer != null) {
				IWorkspace[] workspace = null;
				Object obj = viewer.getInput();
				if (obj instanceof IWorkspace) {
					workspace = new IWorkspace[] { (IWorkspace) obj };
				} else if (obj instanceof IContainer) {
					workspace = new IWorkspace[] { ((IContainer) obj)
							.getWorkspace() };
				} else if (obj instanceof IWorkingSet) {
					IWorkingSet newWorkingSet = (IWorkingSet) obj;
					workspace = getWorkspaces(newWorkingSet);
				}

				if (workspace != null) {
					for (IWorkspace w : workspace) {
						w.removeResourceChangeListener(this);
					}
				}
			}

		} catch (Exception e) {
			Log.log(e);
		}

		try {
			PilarNatureListenersManager.removePilarNatureListener(this);
		} catch (Exception e) {
			Log.log(e);
		}

		try {
			this.topLevelChoice.dispose();
		} catch (Exception e) {
			Log.log(e);
		}

		try {
			super.dispose();
		} catch (Exception e) {
			Log.log(e);
		}
	}

	/*
	 * (non-Javadoc) Method declared on IContentProvider.
	 */
	@Override
	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		super.inputChanged(viewer, oldInput, newInput);

		this.viewer = (CommonViewer) viewer;

		// whenever the input changes, we must reconfigure the top level choice
		topLevelChoice.init(aConfig, this.viewer);

		IWorkspace[] oldWorkspace = null;
		IWorkspace[] newWorkspace = null;

		// get the old
		if (oldInput instanceof IWorkspace) {
			oldWorkspace = new IWorkspace[] { (IWorkspace) oldInput };
		} else if (oldInput instanceof IResource) {
			oldWorkspace = new IWorkspace[] { ((IResource) oldInput)
					.getWorkspace() };
		} else if (oldInput instanceof IWrappedResource) {
			IWrappedResource iWrappedResource = (IWrappedResource) oldInput;
			Object actualObject = iWrappedResource.getActualObject();
			if (actualObject instanceof IResource) {
				IResource iResource = (IResource) actualObject;
				oldWorkspace = new IWorkspace[] { iResource.getWorkspace() };
			}
		} else if (oldInput instanceof IWorkingSet) {
			IWorkingSet oldWorkingSet = (IWorkingSet) oldInput;
			oldWorkspace = getWorkspaces(oldWorkingSet);
		}

		// and the new
		if (newInput instanceof IWorkspace) {
			newWorkspace = new IWorkspace[] { (IWorkspace) newInput };
		} else if (newInput instanceof IResource) {
			newWorkspace = new IWorkspace[] { ((IResource) newInput)
					.getWorkspace() };
		} else if (newInput instanceof IWrappedResource) {
			IWrappedResource iWrappedResource = (IWrappedResource) newInput;
			Object actualObject = iWrappedResource.getActualObject();
			if (actualObject instanceof IResource) {
				IResource iResource = (IResource) actualObject;
				newWorkspace = new IWorkspace[] { iResource.getWorkspace() };
			}
		} else if (newInput instanceof IWorkingSet) {
			IWorkingSet newWorkingSet = (IWorkingSet) newInput;
			newWorkspace = getWorkspaces(newWorkingSet);
		}

		// now, let's treat the workspace
		if (oldWorkspace != null) {
			for (IWorkspace workspace : oldWorkspace) {
				workspace.removeResourceChangeListener(this);
			}
		}
		if (newWorkspace != null) {
			for (IWorkspace workspace : newWorkspace) {
				workspace.addResourceChangeListener(this,
						IResourceChangeEvent.POST_CHANGE);
			}
		}
		this.input = newWorkspace;
	}

	/**
	 * @param newWorkingSet
	 */
	private IWorkspace[] getWorkspaces(IWorkingSet newWorkingSet) {
		IAdaptable[] elements = newWorkingSet.getElements();
		HashSet<IWorkspace> set = new HashSet<IWorkspace>();

		for (IAdaptable adaptable : elements) {
			IResource adapter = (IResource) adaptable
					.getAdapter(IResource.class);
			if (adapter != null) {
				IWorkspace workspace = adapter.getWorkspace();
				set.add(workspace);
			} else {
				Log.log("Was not expecting that IWorkingSet adaptable didn't return anything...");
			}
		}
		return set.toArray(new IWorkspace[0]);
	}

	/*
	 * (non-Javadoc) Method declared on IResourceChangeListener.
	 */
	@Override
	public final void resourceChanged(final IResourceChangeEvent event) {
		processDelta(event.getDelta());
	}

	/**
	 * Process the resource delta.
	 *
	 * @param delta
	 */
	protected void processDelta(IResourceDelta delta) {
		Control ctrl = viewer.getControl();
		if (ctrl == null || ctrl.isDisposed()) {
			return;
		}

		final Collection<Runnable> runnables = new ArrayList<Runnable>();
		processDelta(delta, runnables);
		processRunnables(runnables);
	}

	/**
	 * @param runnables
	 */
	private void processRunnables(final Collection<Runnable> runnables) {
		if (viewer == null) {
			return;
		}

		Control ctrl = viewer.getControl();
		if (ctrl == null || ctrl.isDisposed()) {
			return;
		}
		if (runnables.isEmpty()) {
			return;
		}

		// Are we in the UIThread? If so spin it until we are done
		if (ctrl.getDisplay().getThread() == Thread.currentThread()) {
			runUpdates(runnables);
		} else {
			ctrl.getDisplay().asyncExec(new Runnable() {
				@Override
				public void run() {
					runUpdates(runnables);
				}
			});
		}
	}

	private final Object lock = new Object();
	private final Collection<Runnable> delayedRunnableUpdates = new ArrayList<Runnable>(); // Vector
																							// because
																							// we
																							// want
																							// it
																							// synchronized!

	/**
	 * Run all of the runnable that are the widget updates (or delay them to the
	 * next request).
	 */
	private void runUpdates(Collection<Runnable> runnables) {
		// Abort if this happens after disposes
		Control ctrl = viewer.getControl();
		if (ctrl == null || ctrl.isDisposed()) {
			synchronized (lock) {
				delayedRunnableUpdates.clear();
			}
			return;
		}

		synchronized (lock) {
			delayedRunnableUpdates.addAll(runnables);
		}
		if (viewer.isBusy()) {
			return; // leave it for the next update!
		}
		ArrayList<Runnable> runnablesToRun = new ArrayList<Runnable>();
		synchronized (lock) {
			runnablesToRun.addAll(delayedRunnableUpdates);
			delayedRunnableUpdates.clear();
		}
		Iterator<Runnable> runnableIterator = runnablesToRun.iterator();
		while (runnableIterator.hasNext()) {
			Runnable runnable = runnableIterator.next();
			runnable.run();
		}
	}

	private final IResource[] EMPTY_RESOURCE_ARRAY = new IResource[0];

	/**
	 * Process a resource delta. Add any runnables
	 */
	private void processDelta(final IResourceDelta delta,
			final Collection<Runnable> runnables) {
		// he widget may have been destroyed
		// by the time this is run. Check for this and do nothing if so.
		Control ctrl = viewer.getControl();
		if (ctrl == null || ctrl.isDisposed()) {
			return;
		}

		// Get the affected resource
		final IResource resource = delta.getResource();

		// If any children have changed type, just do a full refresh of this
		// parent,
		// since a simple update on such children won't work,
		// and trying to map the change to a remove and add is too dicey.
		// The case is: folder A renamed to existing file B, answering yes to
		// overwrite B.
		IResourceDelta[] affectedChildren = delta
				.getAffectedChildren(IResourceDelta.CHANGED);
		for (int i = 0; i < affectedChildren.length; i++) {
			if ((affectedChildren[i].getFlags() & IResourceDelta.TYPE) != 0) {
				runnables.add(getRefreshRunnable(resource));
				return;
			}
		}

		// Opening a project just affects icon, but we need to refresh when
		// a project is closed because if child items have not yet been created
		// in the tree we still need to update the item's children
		int changeFlags = delta.getFlags();
		if ((changeFlags & IResourceDelta.OPEN) != 0) {
			if (resource.isAccessible()) {
				runnables.add(getUpdateRunnable(resource));
			} else {
				runnables.add(getRefreshRunnable(resource));
				return;
			}
		}
		// Check the flags for changes the Navigator cares about.
		// See ResourceLabelProvider for the aspects it cares about.
		// Notice we don't care about F_CONTENT or F_MARKERS currently.
		if ((changeFlags & (IResourceDelta.SYNC | IResourceDelta.TYPE | IResourceDelta.DESCRIPTION)) != 0) {
			runnables.add(getUpdateRunnable(resource));
		}
		// Replacing a resource may affect its label and its children
		if ((changeFlags & IResourceDelta.REPLACED) != 0) {
			runnables.add(getRefreshRunnable(resource));
			return;
		}

		// Replacing a resource may affect its label and its children
		if ((changeFlags & (IResourceDelta.CHANGED | IResourceDelta.CONTENT)) != 0) {
			if (resource instanceof IFile) {
				IFile file = (IFile) resource;
				if (PilarPathHelper.isValidSourceFile(file)) {
					runnables.add(getRefreshRunnable(resource));
				}
			}
			return;
		}

		// Handle changed children .
		for (int i = 0; i < affectedChildren.length; i++) {
			processDelta(affectedChildren[i], runnables);
		}

		// @issue several problems here:
		// - should process removals before additions, to avoid multiple equal
		// elements in viewer
		// - Kim: processing removals before additions was the indirect cause of
		// 44081 and its varients
		// - Nick: no delta should have an add and a remove on the same element,
		// so processing adds first is probably OK
		// - using setRedraw will cause extra flashiness
		// - setRedraw is used even for simple changes
		// - to avoid seeing a rename in two stages, should turn redraw on/off
		// around combined removal and addition
		// - Kim: done, and only in the case of a rename (both remove and add
		// changes in one delta).

		IResourceDelta[] addedChildren = delta
				.getAffectedChildren(IResourceDelta.ADDED);
		IResourceDelta[] removedChildren = delta
				.getAffectedChildren(IResourceDelta.REMOVED);

		if (addedChildren.length == 0 && removedChildren.length == 0) {
			return;
		}

		final IResource[] addedObjects;
		final IResource[] removedObjects;

		// Process additions before removals as to not cause selection
		// preservation prior to new objects being added
		// Handle added children. Issue one update for all insertions.
		int numMovedFrom = 0;
		int numMovedTo = 0;
		if (addedChildren.length > 0) {
			addedObjects = new IResource[addedChildren.length];
			for (int i = 0; i < addedChildren.length; i++) {
				final IResourceDelta addedChild = addedChildren[i];
				addedObjects[i] = addedChild.getResource();
				if ((addedChild.getFlags() & IResourceDelta.MOVED_FROM) != 0) {
					++numMovedFrom;
				}
			}
		} else {
			addedObjects = EMPTY_RESOURCE_ARRAY;
		}

		// Handle removed children. Issue one update for all removals.
		if (removedChildren.length > 0) {
			removedObjects = new IResource[removedChildren.length];
			for (int i = 0; i < removedChildren.length; i++) {
				final IResourceDelta removedChild = removedChildren[i];
				removedObjects[i] = removedChild.getResource();
				if ((removedChild.getFlags() & IResourceDelta.MOVED_TO) != 0) {
					++numMovedTo;
				}
			}
		} else {
			removedObjects = EMPTY_RESOURCE_ARRAY;
		}
		// heuristic test for items moving within same folder (i.e. renames)
		final boolean hasRename = numMovedFrom > 0 && numMovedTo > 0;

		Runnable addAndRemove = new Runnable() {
			@Override
			public void run() {
				if (viewer instanceof AbstractTreeViewer) {
					AbstractTreeViewer treeViewer = viewer;
					// Disable redraw until the operation is finished so we
					// don't
					// get a flash of both the new and old item (in the case of
					// rename)
					// Only do this if we're both adding and removing files (the
					// rename case)
					if (hasRename) {
						treeViewer.getControl().setRedraw(false);
					}
					try {
						Set<IProject> notifyRebuilt = new HashSet<IProject>();

						// now, we have to make a bridge among the tree and
						// the pilar model (so, if some element is removed,
						// we have to create an actual representation for it)
						if (addedObjects.length > 0) {
							treeViewer.add(resource, addedObjects);
							for (Object object : addedObjects) {
								if (object instanceof IResource) {
									IResource rem = (IResource) object;
									Object remInPilarModel = getResourceInPilarModel(
											rem, true);
									if (remInPilarModel instanceof PilarSourceFolder) {
										notifyRebuilt.add(rem.getProject());
									}
								}
							}
						}

						if (removedObjects.length > 0) {
							treeViewer.remove(removedObjects);
							for (Object object : removedObjects) {
								if (object instanceof IResource) {
									IResource rem = (IResource) object;
									Object remInPilarModel = getResourceInPilarModel(
											rem, true);
									if (remInPilarModel instanceof PilarSourceFolder) {
										notifyRebuilt.add(rem.getProject());
									}
								}
							}
						}

						for (IProject project : notifyRebuilt) {
							PilarNature nature = PilarNature
									.getPilarNature(project);
							if (nature != null) {
								notifyPilarPathRebuilt(project, nature);
							}
						}
					} finally {
						if (hasRename) {
							treeViewer.getControl().setRedraw(true);
						}
					}
				} else {
					((StructuredViewer) viewer).refresh(resource);
				}
			}
		};
		runnables.add(addAndRemove);
	}

	/**
	 * Return a runnable for refreshing a resource. Handles structural changes.
	 */
	private Runnable getRefreshRunnable(final IResource resource) {
		return new Runnable() {
			@Override
			public void run() {
				((StructuredViewer) viewer)
						.refresh(getResourceInPilarModel(resource));
			}
		};
	}

	/**
	 * Return a runnable for updating a resource. Does not handle structural
	 * changes.
	 */
	private Runnable getUpdateRunnable(final IResource resource) {
		return new Runnable() {
			@Override
			public void run() {
				((StructuredViewer) viewer).update(
						getResourceInPilarModel(resource), null);
			}
		};
	}

}
