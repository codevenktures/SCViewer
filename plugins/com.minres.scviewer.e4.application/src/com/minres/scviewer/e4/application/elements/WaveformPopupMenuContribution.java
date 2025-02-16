 
package com.minres.scviewer.e4.application.elements;

import java.util.List;

import javax.inject.Inject;

import org.eclipse.e4.core.di.annotations.Evaluate;
import org.eclipse.e4.ui.di.AboutToHide;
import org.eclipse.e4.ui.di.AboutToShow;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.commands.MCommand;
import org.eclipse.e4.ui.model.application.commands.MCommandsFactory;
import org.eclipse.e4.ui.model.application.commands.MParameter;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.menu.ItemType;
import org.eclipse.e4.ui.model.application.ui.menu.MHandledMenuItem;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuElement;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuFactory;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;

import com.minres.scviewer.database.BitVector;
import com.minres.scviewer.database.DoubleVal;
import com.minres.scviewer.database.WaveformType;
import com.minres.scviewer.database.ui.TrackEntry;
import com.minres.scviewer.e4.application.Messages;
import com.minres.scviewer.e4.application.parts.WaveformViewer;

public class WaveformPopupMenuContribution {
	int counter=0;
	
	@Inject MPart activePart;
		
	final TrackEntry nullEntry = new TrackEntry();
	
	private boolean selHasBitVector(ISelection sel, boolean checkForDouble) {
		if(!sel.isEmpty() && sel instanceof IStructuredSelection) {
			for(Object elem:(IStructuredSelection)sel) {
				if(elem instanceof TrackEntry) {
					TrackEntry e = (TrackEntry) elem;
					if(e.waveform.getType() == WaveformType.SIGNAL) {
						Object o = e.waveform.getEvents().firstEntry().events[0];
						if(checkForDouble && o instanceof DoubleVal) 
							return true;
						else if(o instanceof BitVector && ((BitVector)o).getWidth()>1)
							return true;
						else
							return false;
					}
				}
			}
		}
		return false;
	}
	
	private TrackEntry getSingleTrackEntry(ISelection sel) {
		TrackEntry entry=null;
		if(!sel.isEmpty() && sel instanceof IStructuredSelection) {
			for(Object elem:(IStructuredSelection)sel) {
				if(elem instanceof TrackEntry) {
					if(entry != null)
						return null;
					entry = (TrackEntry) elem;
				}
			}
		}
		return entry;
	}
	
	@Evaluate
	public boolean evaluate() {
		Object obj = activePart.getObject();
		if(obj instanceof WaveformViewer){
			WaveformViewer wfv = (WaveformViewer)obj;
			return selHasBitVector(wfv.getSelection(), true);
		}
		return false;
	}

	@AboutToShow
	public void aboutToShow(List<MMenuElement> items, MApplication application, EModelService modelService) {
		Object obj = activePart.getObject();
		if(obj instanceof WaveformViewer){
			WaveformViewer wfv = (WaveformViewer)obj;
			ISelection sel = wfv.getSelection();
			TrackEntry elem = getSingleTrackEntry(sel);
			if(selHasBitVector(sel, false)) {
				addValueMenuItem(items, application, modelService, Messages.WaveformPopupMenuContribution_0, TrackEntry.ValueDisplay.BINARY, elem);
				addValueMenuItem(items, application, modelService, Messages.WaveformPopupMenuContribution_1, TrackEntry.ValueDisplay.DEFAULT, elem);
				addValueMenuItem(items, application, modelService, Messages.WaveformPopupMenuContribution_2, TrackEntry.ValueDisplay.UNSIGNED, elem);
				addValueMenuItem(items, application, modelService, Messages.WaveformPopupMenuContribution_3, TrackEntry.ValueDisplay.SIGNED, elem);
				items.add(MMenuFactory.INSTANCE.createMenuSeparator());
				addWaveMenuItem(items, application, modelService, Messages.WaveformPopupMenuContribution_4, TrackEntry.WaveDisplay.DEFAULT, elem);
			}						
			addWaveMenuItem(items, application, modelService, Messages.WaveformPopupMenuContribution_5, TrackEntry.WaveDisplay.STEP_WISE, elem);
			addWaveMenuItem(items, application, modelService, Messages.WaveformPopupMenuContribution_6, TrackEntry.WaveDisplay.CONTINOUS, elem);
		}
	}

	private void addValueMenuItem(List<MMenuElement> items, MApplication application, EModelService modelService,
			String label, TrackEntry.ValueDisplay value, TrackEntry elem) {
		MHandledMenuItem item = MMenuFactory.INSTANCE.createHandledMenuItem();
		item.setType(ItemType.RADIO);
		item.setSelected(elem != null && elem.valueDisplay == value);
		item.setLabel(NLS.bind(Messages.WaveformPopupMenuContribution_7, label));
		item.setContributorURI("platform:/plugin/com.minres.scviewer.e4.application"); //$NON-NLS-1$
		List<MCommand> cmds = modelService.findElements(application, "com.minres.scviewer.e4.application.command.changevaluedisplay", MCommand.class, null); //$NON-NLS-1$
		if(cmds.size()!=1) System.err.println(Messages.WaveformPopupMenuContribution_10);
		else item.setCommand(cmds.get(0));
		MParameter param = MCommandsFactory.INSTANCE.createParameter();
		param.setName("com.minres.scviewer.e4.application.commandparameter.changevaluedisplay"); //$NON-NLS-1$
		param.setValue(value.toString());
		item.getParameters().add(param);
		items.add(item);
	}
	
	private void addWaveMenuItem(List<MMenuElement> items, MApplication application, EModelService modelService,
			String label, TrackEntry.WaveDisplay value, TrackEntry elem) {
		MHandledMenuItem item = MMenuFactory.INSTANCE.createHandledMenuItem();
		item.setType(ItemType.RADIO);
		item.setSelected(elem != null && elem.waveDisplay==value);
		item.setLabel(NLS.bind(Messages.WaveformPopupMenuContribution_12, label));
		item.setContributorURI("platform:/plugin/com.minres.scviewer.e4.application"); //$NON-NLS-1$
		List<MCommand> cmds = modelService.findElements(application, "com.minres.scviewer.e4.application.command.changewavedisplay", MCommand.class, null); //$NON-NLS-1$
		if(cmds.size()!=1) System.err.println(Messages.WaveformPopupMenuContribution_15);
		else item.setCommand(cmds.get(0));
		MParameter param = MCommandsFactory.INSTANCE.createParameter();
		param.setName("com.minres.scviewer.e4.application.commandparameter.changewavedisplay"); //$NON-NLS-1$
		param.setValue(value.toString());
		item.getParameters().add(param);
		items.add(item);
	}
	
	@AboutToHide
	public void aboutToHide(List<MMenuElement> items) {
		
	}
		
}