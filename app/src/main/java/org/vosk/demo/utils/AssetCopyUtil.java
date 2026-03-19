package org.vosk.demo.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AssetCopyUtil {
    private static final String TAG = "AssetCopyUtil";

    public static void copyAssetFolderToInternalStorage(Context context, String assetPath, File destDir, CopyListener listener) {
        new CopyTask(context, assetPath, destDir, listener).execute();
    }

    private static class CopyTask extends AsyncTask<Void, Integer, Boolean> {
        private Context context;
        private String assetPath;
        private File destDir;
        private CopyListener listener;
        private long totalSize = 0;
        private long copiedSize = 0;

        CopyTask(Context context, String assetPath, File destDir, CopyListener listener) {
            this.context = context;
            this.assetPath = assetPath;
            this.destDir = destDir;
            this.listener = listener;
        }

        @Override
        protected void onPreExecute() {
            if (listener != null) listener.onStart();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            AssetManager assetManager = context.getAssets();
            try {
                totalSize = getFolderSize(assetManager, assetPath);
                return copyFolder(assetManager, assetPath, destDir);
            } catch (IOException e) {
                Log.e(TAG, "复制失败", e);
                return false;
            }
        }

        private boolean copyFolder(AssetManager assetManager, String assetPath, File destDir) throws IOException {
            String[] files = assetManager.list(assetPath);
            if (files == null) return false;

            if (!destDir.exists() && !destDir.mkdirs()) {
                Log.e(TAG, "无法创建目录: " + destDir);
                return false;
            }

            for (String filename : files) {
                String childAssetPath = assetPath.isEmpty() ? filename : assetPath + "/" + filename;
                File childDest = new File(destDir, filename);

                if (isDirectory(assetManager, childAssetPath)) {
                    if (!copyFolder(assetManager, childAssetPath, childDest)) {
                        return false;
                    }
                } else {
                    try (InputStream in = assetManager.open(childAssetPath);
                         OutputStream out = new FileOutputStream(childDest)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            copiedSize += read;
                            if (totalSize > 0) {
                                int progress = (int) (copiedSize * 100 / totalSize);
                                publishProgress(progress);
                            }
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "复制文件失败: " + childAssetPath, e);
                        return false;
                    }
                }
            }
            return true;
        }

        private long getFolderSize(AssetManager assetManager, String assetPath) throws IOException {
            long size = 0;
            String[] files = assetManager.list(assetPath);
            if (files == null) return 0;
            for (String file : files) {
                String childPath = assetPath + "/" + file;
                if (isDirectory(assetManager, childPath)) {
                    size += getFolderSize(assetManager, childPath);
                } else {
                    // 无法直接获取 assets 文件大小，这里简单返回0，不影响进度
                }
            }
            return size;
        }

        private boolean isDirectory(AssetManager assetManager, String path) {
            try {
                String[] list = assetManager.list(path);
                return list != null && list.length > 0;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (listener != null) listener.onProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (listener != null) {
                if (success) {
                    listener.onSuccess(destDir);
                } else {
                    listener.onError();
                }
            }
        }
    }

    public interface CopyListener {
        void onStart();
        void onProgress(int percent);
        void onSuccess(File destDir);
        void onError();
    }
}