/*******************************************************************************
 * Copyright (c) 2015-2021 MINRES Technologies GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     MINRES Technologies GmbH - initial API and implementation
 *******************************************************************************/
package com.minres.scviewer.e4.application.parts;

import java.beans.PropertyChangeListener;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.services.EMenuService;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ITreePathContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.wb.swt.ResourceManager;
import org.eclipse.wb.swt.SWTResourceManager;

import com.minres.scviewer.database.HierNode;
import com.minres.scviewer.database.IHierNode;
import com.minres.scviewer.database.IWaveform;
import com.minres.scviewer.database.IWaveformDb;
import com.minres.scviewer.e4.application.Constants;
import com.minres.scviewer.e4.application.Messages;
import com.minres.scviewer.e4.application.handlers.AddWaveformHandler;
import com.minres.scviewer.e4.application.provider.TxDbContentProvider;
import com.minres.scviewer.e4.application.provider.TxDbLabelProvider;

/**
 * The Class DesignBrowser. It contains the design tree, a list of Streams & signals and a few buttons to 
 * add them them to the waveform view 
 */
public class DesignBrowser {

	/** The Constant POPUP_ID. */
	private static final String POPUP_ID="com.minres.scviewer.e4.application.parts.DesignBrowser.popupmenu"; //$NON-NLS-1$

	private static final String AFTER="after"; //$NON-NLS-1$
	/** The event broker. */
	@Inject IEventBroker eventBroker;

	/** The selection service. */
	@Inject	ESelectionService selectionService;

	/** The menu service. */
	@Inject EMenuService menuService;

	/** The eclipse ctx. */
	@Inject IEclipseContext eclipseCtx;

	/** The sash form. */
	private SashForm sashForm;

	/** The top. */
	Composite top;

	/** The tree viewer. */
	private TreeViewer treeViewer;

	/** The attribute filter. */
	StreamTTreeFilter treeAttributeFilter;

	/** The attribute filter. */
	StreamTableFilter tableAttributeFilter;

	/** The tx table viewer. */
	private TableViewer txTableViewer;

	/** The append all item. */
	ToolItem appendItem;
	
	ToolItem insertItem;

	/** The other selection count. */
	int thisSelectionCount=0;
	
	int otherSelectionCount=0;

	/** The waveform viewer part. */
	private WaveformViewer waveformViewerPart;

	/** The tree viewer pcl. */
	private PropertyChangeListener treeViewerPCL = evt -> {
		if(IHierNode.CHILDS.equals(evt.getPropertyName())){ //$NON-NLS-1$
			treeViewer.getTree().getDisplay().asyncExec(() -> treeViewer.refresh());
		} else if(IHierNode.WAVEFORMS.equals(evt.getPropertyName())) {
			treeViewer.getTree().getDisplay().asyncExec(() -> {
				treeViewer.setInput(new IWaveformDb[]{waveformViewerPart.getDatabase()});
				treeViewer.refresh();
			});
		} else if(IHierNode.LOADING_FINISHED.equals(evt.getPropertyName())) {
			if(!treeViewer.getControl().isDisposed())
				treeViewer.getTree().getDisplay().asyncExec(() -> {
					treeViewer.update(waveformViewerPart.getDatabase(), null);
					DesignBrowser.this.updateButtons();
				});
		}
	};

	/** The sash paint listener. */
	protected PaintListener sashPaintListener= e -> {
		int size=Math.min(e.width, e.height)-1;
		e.gc.setForeground(SWTResourceManager.getColor(SWT.COLOR_DARK_GRAY));
		e.gc.setFillRule(SWT.FILL_EVEN_ODD);
		if(e.width>e.height)
			e.gc.drawArc(e.x+(e.width-size)/2, e.y, size, size, 0, 360);
		else
			e.gc.drawArc(e.x, e.y+(e.height-size)/2, size, size, 0, 360);
	};


	/**
	 * Creates the composite.
	 *
	 * @param parent the parent
	 */
	@PostConstruct
	public void createComposite(Composite parent, @Optional WaveformViewer waveformViewerPart) {
		parent.setLayout(new FillLayout(SWT.HORIZONTAL));
		sashForm = new SashForm(parent, SWT.BORDER | SWT.SMOOTH | SWT.VERTICAL);

		top = new Composite(sashForm, SWT.NONE);
		createTreeViewerComposite(top);
		Composite bottom = new Composite(sashForm, SWT.NONE);
		createTableComposite(bottom);

		sashForm.setWeights(new int[] {100, 100});
		sashForm.SASH_WIDTH=5;
		top.addControlListener(new ControlAdapter() {
			@Override
			public void controlResized(ControlEvent e) {
				sashForm.getChildren()[2].addPaintListener(sashPaintListener);
				top.removeControlListener(this);
			}
		});
		if(waveformViewerPart!=null)
			setWaveformViewer(waveformViewerPart);
	}

	/**
	 * Creates the tree viewer composite.
	 *
	 * @param parent the parent
	 */
	public void createTreeViewerComposite(Composite parent) {
		parent.setLayout(new GridLayout(1, false));

		Text treeNameFilter = new Text(parent, SWT.BORDER);
		treeNameFilter.setMessage(Messages.DesignBrowser_3);
		treeNameFilter.addModifyListener( e -> {
			treeAttributeFilter.setSearchText(((Text) e.widget).getText());
			treeViewer.refresh();
		});
		treeNameFilter.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		treeAttributeFilter = new StreamTTreeFilter();

		treeViewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
		treeViewer.getTree().setLayoutData(new GridData(GridData.FILL_BOTH));
		treeViewer.setContentProvider(new TxDbContentProvider() {
			@Override
			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
				updateButtons();
			}
		});
		treeViewer.setLabelProvider(new TxDbLabelProvider(true));
		treeViewer.addFilter(treeAttributeFilter);
		treeViewer.setUseHashlookup(true);
		treeViewer.setAutoExpandLevel(2);
		treeViewer.addSelectionChangedListener(event -> {
			ISelection selection=event.getSelection();
			if( selection instanceof IStructuredSelection) { 
				Object object= ((IStructuredSelection)selection).getFirstElement();			
				if(object instanceof IHierNode && !((IHierNode)object).getChildNodes().isEmpty()){
					txTableViewer.setInput(object);
					updateButtons();
				}
				else { //if selection is changed but empty
					txTableViewer.setInput(null);
					updateButtons();
				}
			}
		});
	}
	
	public Control getControl() {
		return top;
	}

	/**
	 * Creates the table composite.
	 *
	 * @param parent the parent
	 */
	public void createTableComposite(Composite parent) {
		parent.setLayout(new GridLayout(1, false));

		Text tableNameFilter = new Text(parent, SWT.BORDER);
		tableNameFilter.setMessage(Messages.DesignBrowser_2);
		tableNameFilter.addModifyListener(e -> {
			tableAttributeFilter.setSearchText(((Text) e.widget).getText());
			updateButtons();
			txTableViewer.refresh();
		});
		tableNameFilter.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		tableAttributeFilter = new StreamTableFilter();

		txTableViewer = new TableViewer(parent);
		txTableViewer.setContentProvider(new TxDbContentProvider(true) {
			@Override
			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
				updateButtons();
			}
		});
		txTableViewer.setLabelProvider(new TxDbLabelProvider(false));
		txTableViewer.getTable().setLayoutData(new GridData(GridData.FILL_BOTH));
		txTableViewer.addFilter(tableAttributeFilter);
		txTableViewer.addDoubleClickListener(event -> {
			AddWaveformHandler myHandler = new AddWaveformHandler();
			Object result = runCommand(myHandler, CanExecute.class, AFTER, false); //$NON-NLS-1$
			if(result!=null && (Boolean)result)
				ContextInjectionFactory.invoke(myHandler, Execute.class, eclipseCtx);
		});
		txTableViewer.addSelectionChangedListener(event -> {
			selectionService.setSelection(event.getSelection());
			updateButtons();
		});
		menuService.registerContextMenu(txTableViewer.getControl(), POPUP_ID);

		ToolBar toolBar = new ToolBar(parent, SWT.FLAT | SWT.RIGHT);
		toolBar.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 1, 1));
		toolBar.setBounds(0, 0, 87, 20);

		appendItem = new ToolItem(toolBar, SWT.NONE);
		appendItem.setToolTipText(Messages.DesignBrowser_4);
		appendItem.setImage(ResourceManager.getPluginImage(Constants.PLUGIN_ID, "icons/append_wave.png")); //$NON-NLS-1$
		appendItem.setEnabled(false);
		appendItem.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				AddWaveformHandler myHandler = new AddWaveformHandler();
				Object result = runCommand(myHandler, CanExecute.class, AFTER, false); //$NON-NLS-1$
				if(result!=null && (Boolean)result)
					ContextInjectionFactory.invoke(myHandler, Execute.class, eclipseCtx);
			}

		});

		insertItem = new ToolItem(toolBar, SWT.NONE);
		insertItem.setToolTipText(Messages.DesignBrowser_8);
		insertItem.setImage(ResourceManager.getPluginImage(Constants.PLUGIN_ID, "icons/insert_wave.png")); //$NON-NLS-1$
		insertItem.setEnabled(false);
		insertItem.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				AddWaveformHandler myHandler = new AddWaveformHandler();
				Object result = runCommand(myHandler, CanExecute.class, "before", false); //$NON-NLS-1$
				if(result!=null && (Boolean)result)
					ContextInjectionFactory.invoke(myHandler, Execute.class, eclipseCtx);
			}
		});
	}

	/**
	 * Sets the focus.
	 */
	@Focus
	public void setFocus() {
		if(txTableViewer!=null) {
			txTableViewer.getTable().setFocus();
			IStructuredSelection selection = (IStructuredSelection)txTableViewer.getSelection();
			if(selection.size()==0){
				appendItem.setEnabled(false);
			}
			selectionService.setSelection(selection);
			thisSelectionCount=selection.toList().size();
		}
		updateButtons();
	}

	/** 
	 * reset tree viewer and tableviewer after every closed tab
	 */
	protected void resetTreeViewer() {
		//reset tree- and tableviewer
		treeViewer.setInput(null);
		txTableViewer.setInput(null);
		txTableViewer.setSelection(null);
	}

	public void selectAllWaveforms() {
		int itemCount = txTableViewer.getTable().getItemCount();
		ArrayList<Object> list = new ArrayList<>();
		for(int i=0; i<itemCount; i++) {
			list.add(txTableViewer.getElementAt(i));
		}
		StructuredSelection sel = new StructuredSelection(list);
		txTableViewer.setSelection(sel);
	}

	/**
	 * Gets the status event.
	 *
	 * @param waveformViewerPart the waveform viewer part
	 * @return the status event
	 */
	@Inject @Optional
	public void  getActiveWaveformViewerEvent(@UIEventTopic(WaveformViewer.ACTIVE_WAVEFORMVIEW) WaveformViewer waveformViewerPart) {
		if( this.waveformViewerPart == null || this.waveformViewerPart != waveformViewerPart ) {
			if(this.waveformViewerPart!=null)
				this.waveformViewerPart.storeDesignBrowerState(new DBState());
			waveformViewerPart.addDisposeListener( e -> {
				Control control = treeViewer.getControl();
				// check if widget is already disposed (f.ex. because of workbench closing)
				if (control == null || control.isDisposed()) { //if so: do nothing
				}else {  //reset tree- and tableviewer
					resetTreeViewer();
				}
			});
			setWaveformViewer(waveformViewerPart);
		}
	}

	@SuppressWarnings("unchecked")
	public void setWaveformViewer(WaveformViewer waveformViewerPart) {
		this.waveformViewerPart=waveformViewerPart;
		IWaveformDb database = waveformViewerPart.getDatabase();
		Object input = treeViewer.getInput();
		if(input instanceof List<?>){
			IWaveformDb db = ((List<IWaveformDb>)input).get(0);
			if(db==database) return; // do nothing if old and new database is the same
			((List<IWaveformDb>)input).get(0).removePropertyChangeListener(treeViewerPCL);
		}
		treeViewer.setInput(new IWaveformDb[]{database});
		Object state=this.waveformViewerPart.retrieveDesignBrowerState();
		if(state instanceof DBState) 
			((DBState)state).apply();
		else 
			txTableViewer.setInput(null);
		// Set up the tree viewer
		database.addPropertyChangeListener(treeViewerPCL);
	} 

	/**
	 * Update buttons.
	 */
	private void updateButtons() {
		if(txTableViewer!=null && !insertItem.isDisposed() && !appendItem.isDisposed()){
			AddWaveformHandler myHandler = new AddWaveformHandler();
			Object result = runCommand(myHandler, CanExecute.class, AFTER, false); //$NON-NLS-1$
			appendItem.setEnabled(result instanceof Boolean && (Boolean)result);
			result = runCommand(myHandler, CanExecute.class, "before", false); //$NON-NLS-1$
			insertItem.setEnabled(result instanceof Boolean && (Boolean)result);
		}
	}

	/**
	 * The Class StreamTableFilter.
	 */
	public class StreamTableFilter extends ViewerFilter {

		/** The search string. */
		private String searchString;
		private Pattern pattern;

		/**
		 * Sets the search text.
		 *
		 * @param s the new search text
		 */
		public void setSearchText(String s) {
			try {
				pattern = Pattern.compile(".*" + s + ".*"); //$NON-NLS-1$ //$NON-NLS-2$
				this.searchString = ".*" + s + ".*"; //$NON-NLS-1$ //$NON-NLS-2$
			} catch (PatternSyntaxException e) {}
		}

		/* (non-Javadoc)
		 * @see org.eclipse.jface.viewers.ViewerFilter#select(org.eclipse.jface.viewers.Viewer, java.lang.Object, java.lang.Object)
		 */
		@Override
		public boolean select(Viewer viewer, Object parentElement, Object element) {
			return searchString == null ||
					searchString.length() == 0 ||
					(element instanceof IWaveform && pattern.matcher(((IWaveform) element).getName()).matches());
		}
	}

	public class StreamTTreeFilter extends ViewerFilter {

		/** The search string. */
		private String searchString;
		private Pattern pattern;

		/**
		 * Sets the search text.
		 *
		 * @param s the new search text
		 */
		public void setSearchText(String s) {
			try {
				pattern = Pattern.compile(".*" + s + ".*");
				this.searchString = ".*" + s + ".*"; //$NON-NLS-1$ //$NON-NLS-2$
			} catch (PatternSyntaxException e) {}

		}

		/* (non-Javadoc)
		 * @see org.eclipse.jface.viewers.ViewerFilter#select(org.eclipse.jface.viewers.Viewer, java.lang.Object, java.lang.Object)
		 */
		@Override
		public boolean select(Viewer viewer, Object parentElement, Object element) {
			return selectTreePath(viewer, new TreePath(new Object[] { parentElement }), element);
		}

		private boolean selectTreePath(Viewer viewer, TreePath parentPath, Object element) {
			// Cut off children of elements that are shown repeatedly.
			for (int i = 0; i < parentPath.getSegmentCount() - 1; i++) {
				if (element.equals(parentPath.getSegment(i))) {
					return false;
				}
			}

			if (!(viewer instanceof TreeViewer)) {
				return true;
			}
			if (searchString == null || searchString.length() == 0) {
				return true;
			}
			Boolean matchingResult = isMatchingOrNull(element);
			if (matchingResult != null) {
				return matchingResult;
			}
			return hasUnfilteredChild((TreeViewer)viewer, parentPath, element);
		}

		Boolean isMatchingOrNull(Object element) {
			if(element instanceof IWaveform) {
				if (pattern.matcher(((IWaveform) element).getName()).matches())
					return Boolean.TRUE;
			} else if(element instanceof IWaveformDb) {
				return Boolean.TRUE;
			} else if(element instanceof HierNode) {
				HierNode n = (HierNode) element;
				try {
					if (pattern.matcher(n.getFullName()).matches())
						return Boolean.TRUE;
				} catch (PatternSyntaxException e) {
					return Boolean.TRUE;
				}
			} else {
				return Boolean.FALSE;
			}
			/* maybe children are matching */
			return null;
		}

		private boolean hasUnfilteredChild(TreeViewer viewer, TreePath parentPath, Object element) {
			TreePath elementPath = parentPath.createChildPath(element);
			IContentProvider contentProvider = viewer.getContentProvider();
			Object[] children = contentProvider instanceof ITreePathContentProvider
					? ((ITreePathContentProvider) contentProvider).getChildren(elementPath)
							: ((ITreeContentProvider) contentProvider).getChildren(element);

					/* avoid NPE + guard close */
					if (children == null || children.length == 0) {
						return false;
					}
					for (int i = 0; i < children.length; i++) {
						if (selectTreePath(viewer, elementPath, children[i])) {
							return true;
						}
					}
					return false;
		}
	}
	/**
	 * Gets the filtered children.
	 *
	 * @param viewer the viewer
	 * @return the filtered children
	 */
	protected Object[] getFilteredChildren(TableViewer viewer){
		Object parent = viewer.getInput();
		if(parent==null) return new Object[0];
		Object[] result = null;
		IStructuredContentProvider cp = (IStructuredContentProvider) viewer.getContentProvider();
		if (cp != null) {
			result = cp.getElements(parent);
			if(result==null) return new Object[0];
			for (int i = 0, n = result.length; i < n; ++i) {
				if(result[i]==null) return new Object[0];
			}
		}
		ViewerFilter[] filters = viewer.getFilters();
		if (filters != null) {
			for (ViewerFilter f:filters) {
				Object[] filteredResult = f.filter(viewer, parent, result);
				result = filteredResult;
			}
		}
		return result;
	}

	/**
	 * Run command.
	 *
	 * @param handler the handler
	 * @param annotation the annotation
	 * @param where the where
	 * @param all the all
	 * @return the object
	 */
	protected Object runCommand(AddWaveformHandler handler, Class<? extends Annotation> annotation, String where, Boolean all) {
		ContextInjectionFactory.inject(handler, eclipseCtx);
		eclipseCtx.set(AddWaveformHandler.PARAM_WHERE_ID, where);
		eclipseCtx.set(AddWaveformHandler.PARAM_ALL_ID, all.toString());
		eclipseCtx.set(DesignBrowser.class, this);
		eclipseCtx.set(WaveformViewer.class, waveformViewerPart);
		return ContextInjectionFactory.invoke(handler, annotation, eclipseCtx);
	}

	/**
	 * Gets the filtered children.
	 *
	 * @return the filtered children
	 */
	public Object[] getFilteredChildren() {
		return getFilteredChildren(txTableViewer);
	}

	/**
	 * Gets the active waveform viewer part.
	 *
	 * @return the active waveform viewer part
	 */
	public WaveformViewer getActiveWaveformViewerPart() {
		return waveformViewerPart;
	}

	/**
	 * The Class DBState.
	 */
	class DBState {

		/**
		 * Instantiates a new DB state.
		 */
		public DBState() {
			this.expandedElements=treeViewer.getExpandedElements();
			this.treeSelection=treeViewer.getSelection();
			this.tableSelection=txTableViewer.getSelection();
		}

		/**
		 * Apply.
		 */
		public void apply() {
			if(treeViewer.getControl().isDisposed()) return;
			treeViewer.setExpandedElements(expandedElements);
			treeViewer.setSelection(treeSelection, true);
			txTableViewer.setSelection(tableSelection, true);

		}

		/** The expanded elements. */
		private Object[] expandedElements;

		/** The tree selection. */
		private ISelection treeSelection;

		/** The table selection. */
		private ISelection tableSelection;
	}
}