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

import java.awt.Desktop;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.wb.swt.ResourceManager;
import org.eclipse.wb.swt.SWTResourceManager;
import org.osgi.framework.Version;

import com.minres.scviewer.e4.application.Constants;
import com.minres.scviewer.e4.application.Messages;

/**
 * The Class AboutDialog.
 */
public class AboutDialog extends Dialog {

	/** The copyright text. */
	private String copyrightText=Messages.AboutDialog_1;

	/**
	 * Create the dialog.
	 *
	 * @param parentShell the parent shell
	 */
	@Inject
	public AboutDialog(Shell parentShell) {
		super(parentShell);
		
	}

	/**
	 * Create contents of the dialog.
	 *
	 * @param parent the parent
	 * @return the control
	 */
	@Override
	protected Control createDialogArea(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		GridData gdComposite = new GridData(SWT.LEFT, SWT.FILL, true, true);
		gdComposite.widthHint = 600;
		gdComposite.heightHint =300;
		composite.setLayoutData(gdComposite);
		composite.setLayout(new GridLayout(2, false));
		
		final Color white=SWTResourceManager.getColor(SWT.COLOR_WHITE);
		final Image scviewerLogo=ResourceManager.getPluginImage(Constants.PLUGIN_ID, "icons/SCViewer_logo.png"); //$NON-NLS-1$
		final Image minresLogo=ResourceManager.getPluginImage(Constants.PLUGIN_ID, "icons/Minres_logo.png"); //$NON-NLS-1$

		Canvas canvas = new Canvas(composite,SWT.NO_REDRAW_RESIZE);
		GridData gdCanvas = new GridData(SWT.LEFT, SWT.CENTER, false, false, 1, 1);
		gdCanvas.widthHint = 200;
		gdCanvas.heightHint =300;
		canvas.setLayoutData(gdCanvas);
		canvas.addPaintListener(e -> {
				e.gc.setBackground(white);
				e.gc.fillRectangle(e.x, e.y, e.width, e.height);
				e.gc.drawImage(scviewerLogo,4,0);
				e.gc.drawImage(minresLogo,0,200);
		});

		StyledText styledText = new StyledText(composite, SWT.V_SCROLL | SWT.BORDER);
		styledText.setEditable(false);
		GridData gdStyledText = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1);
		styledText.setLayoutData(gdStyledText);
		Version version = Platform.getProduct().getDefiningBundle().getVersion();
		String versionString = String.format("%d.%d.%d", version.getMajor(), version.getMinor(), version.getMicro());
		String productTitle = NLS.bind(Messages.AboutDialog_0, versionString);
		styledText.setText(productTitle+copyrightText);
		styledText.setBackground(white);
		styledText.setWordWrap(true);
		styledText.setLeftMargin(5);
		StyleRange styleRange = new StyleRange();
		styleRange.start = 0;
		styleRange.length = productTitle.length();
		styleRange.fontStyle = SWT.BOLD;
		styledText.setStyleRange(styleRange);
		///^(https?:\/\/)?([\da-z\.-]+)\.([a-z\.]{2,6})([\/\w \.-]*)*\/?$/
	    Pattern pattern = Pattern.compile("https?:\\/\\/([\\da-z\\.-]+)\\.([a-z\\.]{2,6})([\\/\\w\\.-]*)*\\/?"/*, Pattern.CASE_INSENSITIVE*/); //$NON-NLS-1$
	    Matcher matcher = pattern.matcher(productTitle+copyrightText);
	    // check all occurance
	    while (matcher.find()) {
			styleRange = new StyleRange();
			styleRange.underline=true;
			styleRange.underlineStyle = SWT.UNDERLINE_LINK;
			styleRange.data = matcher.group();
			styleRange.start = matcher.start();
			styleRange.length = matcher.end()-matcher.start();
			styledText.setStyleRange(styleRange);
	    }
		styledText.addListener(SWT.MouseDown, event -> {
			// It is up to the application to determine when and how a link should be activated.
			// links are activated on mouse down when the control key is held down 
			try {
				@SuppressWarnings("deprecation")
				int offset = ((StyledText)event.widget).getOffsetAtLocation(new Point (event.x, event.y));
				StyleRange style = ((StyledText)event.widget).getStyleRangeAtOffset(offset);
				if (style != null && style.underline && style.underlineStyle == SWT.UNDERLINE_LINK) {
					Desktop.getDesktop().browse(new java.net.URI(style.data.toString()));
				}
			} catch (IOException | URISyntaxException | IllegalArgumentException e) {
			} catch (UnsupportedOperationException e) {
			}
		});
		
		styleRange.start = 0;
		return composite;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.Dialog#createButtonsForButtonBar(org.eclipse.swt.widgets.Composite)
	 */
	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		// create OK button
		createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL,	true);
	}

	/**
	 * Open the dialog.
	 * @return the result
	 */
	@PostConstruct
	@Override
	public int open() {
		return super.open();
	}
	
}
