package com.spicymango.fanfictionreader.dialogs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.crashlytics.android.Crashlytics;
import com.slezica.tools.async.ManagedAsyncTask;
import com.slezica.tools.async.TaskManagerFragment;
import com.spicymango.fanfictionreader.R;
import com.spicymango.fanfictionreader.util.FileHandler;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.widget.ProgressBar;
import android.widget.Toast;

public class BackUpDialog extends DialogFragment {
	/** The backup file path. For the moment, the file must be in the root*/
	public static final String FILENAME = "FanFiction_backup.bak";

	/** The progress bar in the back up dialog*/
	private ProgressBar mBar;
	
	@Override
	@NonNull
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		setCancelable(false);

		// Add a progress bar to the dialog
		mBar = new ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
		mBar.setId(android.R.id.progress);

		// Create the dialog
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(R.string.diag_back_up);
		builder.setView(mBar);

		if (isSdCardWritable(getActivity())) {
			builder.setMessage(R.string.diag_back_up_external);
		} else {
			builder.setMessage(R.string.diag_back_up_internal);
		}

		return builder.create();
	}

	public static boolean isSdCardWritable(Context context){

		if (FileHandler.isExternalStorageWritable(context)) {
			// User has an sd card with write permissions
			int currentApiVersion = android.os.Build.VERSION.SDK_INT;
			if (currentApiVersion >= android.os.Build.VERSION_CODES.LOLLIPOP) {
				// Lollipop does not allow for sd card access without using the ACTION_OPEN_DOCUMENT_TREE,
				// even though the sd card permission is set.
				return false;
			} else {
				// Other android versions do not require the permission
				return true;
			}
		} else {
			return false;
		}
	}
		
	@Override
	public void onStart() {
		super.onStart();

		// Start the managed async task if it has not been started already.
		Fragment manager = getFragmentManager().findFragmentByTag(TaskManagerFragment.DEFAULT_TAG);
		if (manager == null) {
			new BackUpTask(getActivity()).execute((Void)null);
		}
	}

	/**
	 * A ManagedAsyncTask that will perform a backup.
	 */
	private final static class BackUpTask extends ManagedAsyncTask<Void, Integer, Integer>{
		private int mTotalFiles = 0;
		private int mZippedFiles = 0;

		private final File app_internal[], output;

		private final ArrayList<File> appFiles;
		
		public BackUpTask(FragmentActivity activity) {
			super(activity);
			String s = activity.getApplicationInfo().dataDir;
			app_internal = new File(s).listFiles(new FilesDirFilter());
			
			appFiles = new ArrayList<>(3);

			// Get the path of all the app files in both the sd card, the emulated memory, and the
			// internal memory
			appFiles.add(activity.getFilesDir());

			if (FileHandler.isExternalStorageWritable(activity)) {
				appFiles.add(FileHandler.getExternalFilesDir(activity));
			}

			if (FileHandler.isEmulatedFilesDirWritable()) {
				final File emulatedDir = FileHandler.getEmulatedFilesDir(activity);
				if (emulatedDir != null)
					appFiles.add(emulatedDir);
			}


			// Get the destination path
			if (isSdCardWritable(activity)) {
				// Only true if there is an sd card and android version is less than 5.0
				output = new File(FileHandler.getExternalStorageDirectory(activity), FILENAME);
			} else if (FileHandler.isEmulatedFilesDirWritable()) {
				output = new File(Environment.getExternalStorageDirectory(), FILENAME);
			} else {
				output = null;
				cancel(true);
			}
		}

		@Override
		protected Integer doInBackground(Void... params) {
			int result = R.string.toast_back_up;

			//Count all non-story files
			for (File f : app_internal) {
				mTotalFiles += countFiles(f);
			}
			
			//Count all story files
			for (File f : appFiles) {
				mTotalFiles += countFiles(f);
			}

			// Set the maximum possible progress in the progress bar
			publishProgress(mZippedFiles);

			final FileOutputStream fos;
			ZipOutputStream zos = null;

			byte[] buffer = new byte[1024];

			try {
				fos = new FileOutputStream(output);
				zos = new ZipOutputStream(fos);

				// Zip all files
				for (File f : app_internal) {
					zipDir(zos, f, buffer, f.getName());
				}
				
				for (File f : appFiles) {
					zipDir(zos, f, buffer, f.getName());
				}
				
			} catch (IOException e) {
				Crashlytics.logException(e);
				result = R.string.error_unknown;
			} finally {
				// Note that ZipOutputStream closes the underlying FileOutputStream
				try {
					if (zos != null)
						zos.close();
				} catch (IOException e) {
					Crashlytics.logException(e);
					result = R.string.error_unknown;
				}
			}
			return result;
		}

		@Override
		protected void onProgressUpdate(Integer... values) {
			final FragmentManager manager = getActivity().getSupportFragmentManager();
			BackUpDialog dialog = (BackUpDialog) manager.findFragmentByTag(BackUpDialog.class.getName());

			// On the first progress update, set the progress bar maximum
			if (values[0] == 0) {
				dialog.mBar.setMax(mTotalFiles);
			}

			dialog.mBar.setProgress(values[0]);
		}

		@Override
		protected void onPostExecute(Integer result) {
			Toast toast = Toast.makeText(getActivity(), result, Toast.LENGTH_SHORT);
			toast.show();

			// Fix for android issue 195362, in which files do not show in the MTP file explorer
			// until a device reboot occurs.
			// See https://code.google.com/p/android/issues/detail?id=195362
			MediaScannerConnection.scanFile(getActivity(), new String[]{output.getAbsolutePath()}, null, null);

			FragmentManager manager = getActivity().getSupportFragmentManager();

			DialogFragment diag = (DialogFragment) manager
					.findFragmentByTag(BackUpDialog.class.getName());
			
			diag.dismiss();
			
			manager.beginTransaction()
					.remove(manager
							.findFragmentByTag(TaskManagerFragment.DEFAULT_TAG))
					.commit();
			
		}
		
		/**
		 * Zips all the files and folders present in the supplied directory
		 * @param zos The zipOutputStream
		 * @param dir The parent directory
		 * @param buffer A buffer for the zipping proccess
		 * @param parent The name of the parent path
		 * @throws IOException
		 */
		private void zipDir(ZipOutputStream zos, File dir, byte[] buffer, String parent) throws IOException{

			// If the file is not a directory, do not try to zip it.
			if (!dir.isDirectory()) {
				return;
			}

			// In theory, files[] shouldn't be null since the check above should show that dir is a
			// directory. However, some phones (MYPHONE, MID, and ZTE) will return null on listFiles,
			// hence the check.
			final File[] files = dir.listFiles();
			if (files == null) return;

			for (File file : files) {
				if (file.isDirectory()) {
					// Recursively zip directories
					zipDir(zos, file, buffer, parent + '/' + file.getName());
				} else {
					final FileInputStream in = new FileInputStream(file);
					try {
						final ZipEntry entry = new ZipEntry(parent + '/' + file.getName());
						zos.putNextEntry(entry);

						// Zip the individual file
						int length;
						while ((length = in.read(buffer)) > 0) {
							zos.write(buffer, 0, length);
						}
						zos.closeEntry();

						// Update the progress bar
						mZippedFiles++;
						publishProgress(mZippedFiles);
					} catch (IOException e) {
						throw new IOException(e.getMessage());
					} finally {
						in.close();
					}
				}
			}
		}
	
		/**
		 * Counts how many files are contained in a folder
		 * @param folder The parent folder
		 * @return The total number of files inside the folder
		 */
		private static int countFiles(File folder){
			int count = 0;
			File[] files = folder.listFiles();
			
			if (files == null) return 0;
			
			for (File file : files) {
				if (file.isDirectory()) {
					count += countFiles(file);
				}else{
					count++;
				}
			}
			return count;
		}
	
		/**
		 * A simple file filter that separates saved files from the database and
		 * the settings.
		 * 
		 * @author Michael Chen
		 */
		private final static class FilesDirFilter implements FilenameFilter{
			@Override
			public boolean accept(File dir, String filename) {
				return !filename.equalsIgnoreCase("Files");
			}
		}
	}	
}
