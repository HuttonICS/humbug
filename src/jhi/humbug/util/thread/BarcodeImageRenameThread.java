/*
 *  Copyright 2017 Information and Computational Sciences,
 *  The James Hutton Institute.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package jhi.humbug.util.thread;

import com.google.zxing.*;
import com.google.zxing.Reader;
import com.google.zxing.client.j2se.*;
import com.google.zxing.common.*;
import com.google.zxing.multi.*;

import org.eclipse.core.runtime.*;
import org.eclipse.jface.operation.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.*;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.program.*;
import org.eclipse.swt.widgets.*;

import java.awt.image.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.*;

import javax.imageio.*;

import jhi.humbug.gui.dialog.*;
import jhi.humbug.gui.i18n.*;
import jhi.humbug.util.*;

/**
 * @author Sebastian Raubach
 */
public class BarcodeImageRenameThread implements IRunnableWithProgress
{
	private File                   source;
	private File                   target;
	private DuplicateBarcodeOption duplicateBarcodeOption;
	private MissingBarcodeOption   missingBarcodeOption;
	private List<File> noBarcodeFound = new ArrayList<>();
	private BarcodeFormat restriction;
	private boolean       tryHard;

	public BarcodeImageRenameThread(File source, File target, BarcodeFormat restriction, boolean tryHard)
	{
		this.source = source;
		this.restriction = restriction;
		this.tryHard = tryHard;

		if (target == null)
		{
			target = FileUtils.INSTANCE.createUniqueFolder(source, "renamed");
		}

		if (!target.exists())
		{
			target.mkdirs();
		}

		duplicateBarcodeOption = (DuplicateBarcodeOption) HumbugParameterStore.INSTANCE.get(HumbugParameter.duplicateBarcodeOption);
		missingBarcodeOption = (MissingBarcodeOption) HumbugParameterStore.INSTANCE.get(HumbugParameter.missingBarcodeOption);

		this.target = target;
	}

	@Override
	public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException
	{
		/* Ensure there is a monitor of some sort */
		if (monitor == null)
			monitor = new NullProgressMonitor();

		File[] files = source.listFiles((dir, name) -> FileUtils.INSTANCE.isImage(name));

		if (files != null)
		{
			int workload = files.length;

        	/* Tell the user what you are doing */
			monitor.beginTask(RB.getString(RB.THREAD_IMPORT_TITLE), workload);

			int counter = 0;

			for (File file : files)
			{
				if (monitor.isCanceled())
				{
					monitor.done();
					break;
				}

				monitor.subTask(RB.getString(RB.THREAD_RENAME_IMAGE, ++counter));

				String extension = file.getName().substring(file.getName().lastIndexOf(".") + 1);

				Reader reader = new MultiFormatReader();
				GenericMultipleBarcodeReader greader = new GenericMultipleBarcodeReader(reader);

				Hashtable<DecodeHintType, Object> decodeHints = new Hashtable<>();

				if (tryHard)
					decodeHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

				try
				{
					try
					{
						BufferedImage bfi = ImageIO.read(file);
						LuminanceSource ls = new BufferedImageLuminanceSource(bfi);
						BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(ls));
						Result[] res = greader.decodeMultiple(bmp, decodeHints);

						List<Result> resList = Arrays.stream(res)
													 .filter(r -> {
														 if (restriction != null)
															 return r.getBarcodeFormat() == restriction;
														 else
															 return true;
													 })
													 .collect(Collectors.toList());

						switch (resList.size())
						{
							case 0:
								noBarcodeFound.add(file);

								switch (missingBarcodeOption)
								{
									case COPY:
									/* Just copy the file itself */
										Files.copy(file.toPath(), new File(target, file.getName()).toPath());
										break;
									case SKIP:
									default:
									/* Skip */
								}

								break;
							default:
								String filename;
								switch (duplicateBarcodeOption)
								{
									case PICK_FIRST:
										filename = resList.get(0).getText();
										break;
									case CONCATENATE:
									default:
										filename = resList.stream()
														  .map(Result::getText)
														  .collect(Collectors.joining("-"));
										break;
								}

								File t = FileUtils.INSTANCE.createUniqueFile(target, filename, extension);

							/* Copy the file */
								Files.copy(file.toPath(), t.toPath());
								break;
						}
					}
					catch (NotFoundException e)
					{
						switch (missingBarcodeOption)
						{
							case COPY:
							/* Just copy the file itself */
								Files.copy(file.toPath(), new File(target, file.getName()).toPath());
								break;
							case SKIP:
							default:
							/* Skip */
						}

						noBarcodeFound.add(file);
						e.printStackTrace();
					}
				}

				catch (IOException e)
				{
					noBarcodeFound.add(file);
					e.printStackTrace();
//					DialogUtils.handleException(e);
				}

				monitor.worked(1);
			}
		}

		// If images have been skipped (no barcode was found), report them to the user
		if (noBarcodeFound.size() > 0)
		{
			Display.getDefault().asyncExec(() ->
			{
				ListDialog dialog = new ListDialog(Display.getCurrent().getActiveShell())
				{
					// Listen to double click events and then open the file
					@Override
					protected void onItemDoubleClicked(Object item)
					{
						Program.launch(((File) item).getAbsolutePath());
					}
				};
				dialog.setTitle(RB.getString(RB.DIALOG_RENAME_IMAGE_NO_BARCODE_TITLE));
				dialog.setMessage(RB.getString(RB.DIALOG_RENAME_IMAGE_NO_BARCODE_MESSAGE));
				dialog.setInput(noBarcodeFound);
				dialog.setContentProvider(new ArrayContentProvider());
				dialog.setLabelProvider(new LabelProvider()
				{
					@Override
					public String getText(Object element)
					{
						return ((File) element).getName();
					}
				});
				// Listen to Ctrl+C events and then copy the file paths to the clipboard
				dialog.addListener(SWT.KeyUp, event -> {
					if ((((event.stateMask & SWT.CTRL) == SWT.CTRL) || ((event.stateMask & SWT.COMMAND) == SWT.COMMAND))
							&& event.keyCode == 'c')
					{
						IStructuredSelection selection = dialog.getSelection();

						if (selection.size() > 0)
						{
							List<?> list = selection.toList();
							String result = list.stream()
												.map(o -> ((File) o).getAbsolutePath())
												.collect(Collectors.joining("\n"));

							Clipboard cb = new Clipboard(Display.getDefault());
							cb.setContents(new Object[]{result}, new Transfer[]{TextTransfer.getInstance()});
						}
					}
				});
				dialog.open();
			});
		}
	}

	public enum DuplicateBarcodeOption
	{
		CONCATENATE(RB.SETTING_BARCODE_RENAME_DUPLICATE_CONCATENATE),
		PICK_FIRST(RB.SETTING_BARCODE_RENAME_DUPLICATE_PICK_FIRST);

		private String text;

		DuplicateBarcodeOption(String text)
		{
			this.text = RB.getString(text);
		}

		public String getText()
		{
			return text;
		}
	}

	public enum MissingBarcodeOption
	{
		SKIP(RB.SETTING_BARCODE_RENAME_MISSING_SKIP),
		COPY(RB.SETTING_BARCODE_RENAME_MISSING_COPY);

		private String text;

		MissingBarcodeOption(String text)
		{
			this.text = RB.getString(text);
		}

		public String getText()
		{
			return text;
		}
	}
}