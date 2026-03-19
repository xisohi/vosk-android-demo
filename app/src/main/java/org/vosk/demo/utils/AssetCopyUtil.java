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

    public interface CopyListener {
        void onStart();
        void onProgress(int percent);
        void onSuccess(File destDir);
        void onError();
    }

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
                // 创建目标子目录：destDir/assetPath
                File targetDir = new File(destDir, assetPath);
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    Log.e(TAG, "无法创建目标目录: " + targetDir);
                    return false;
                }
                return copyFolder(assetManager, assetPath, targetDir);
            } catch (IOException e) {
                Log.e(TAG, "复制失败", e);
                return false;
            }
        }

        private boolean copyFolder(AssetManager assetManager, String currentAssetPath, File currentDestDir) throws IOException {
            String[] files = assetManager.list(currentAssetPath);
            if (files == null) return false;

            if (!currentDestDir.exists() && !currentDestDir.mkdirs()) {
                Log.e(TAG, "无法创建目录: " + currentDestDir);
                return false;
            }

            for (String filename : files) {
                String childAssetPath = currentAssetPath.isEmpty() ? filename : currentAssetPath + "/" + filename;
                File childDest = new File(currentDestDir, filename);

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

        private long getFolderSize(AssetManager assetManager, String path) throws IOException {
            long size = 0;
            String[] files = assetManager.list(path);
            if (files == null) return 0;
            for (String file : files) {
                String childPath = path + "/" + file;
                if (isDirectory(assetManager, childPath)) {
                    size += getFolderSize(assetManager, childPath);
                } else {
                    // 无法获取 assets 文件大小，返回0不影响进度
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
                    // 列出解压后的文件（用于调试）
                    File modelDir = new File(destDir, assetPath);
                    if (modelDir.exists()) {
                        String[] files = modelDir.list();
                        Log.d(TAG, "解压完成，目标目录: " + modelDir.getAbsolutePath());
                        if (files != null) {
                            for (String f : files) {
                                Log.d(TAG, "  - " + f);
                            }
                        } else {
                            Log.w(TAG, "目标目录为空或无法列出");
                        }
                    } else {
                        Log.e(TAG, "模型子目录不存在: " + modelDir.getAbsolutePath());
                    }
                    listener.onSuccess(destDir);
                } else {
                    listener.onError();
                }
            }
        }
    }
}