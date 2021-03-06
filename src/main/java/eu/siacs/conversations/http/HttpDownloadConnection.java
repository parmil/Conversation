package eu.siacs.conversations.http;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Longs;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import javax.net.ssl.SSLHandshakeException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileWriterException;
import eu.siacs.conversations.utils.MimeUtils;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static eu.siacs.conversations.http.HttpConnectionManager.EXECUTOR;

public class HttpDownloadConnection implements Transferable {

    private final Message message;
    private final boolean mUseTor;
    private final HttpConnectionManager mHttpConnectionManager;
    private final XmppConnectionService mXmppConnectionService;
    private HttpUrl mUrl;
    private DownloadableFile file;
    private int mStatus = Transferable.STATUS_UNKNOWN;
    private boolean acceptedAutomatically = false;
    private int mProgress = 0;
    private boolean canceled = false;
    private Call mostRecentCall;

    HttpDownloadConnection(Message message, HttpConnectionManager manager) {
        this.message = message;
        this.mHttpConnectionManager = manager;
        this.mXmppConnectionService = manager.getXmppConnectionService();
        this.mUseTor = mXmppConnectionService.useTorToConnect();
    }

    @Override
    public boolean start() {
        if (mXmppConnectionService.hasInternetConnection()) {
            if (this.mStatus == STATUS_OFFER_CHECK_FILESIZE) {
                checkFileSize(true);
            } else {
                download(true);
            }
            return true;
        } else {
            return false;
        }
    }

    public void init(boolean interactive) {
        if (message.isDeleted()) {
            if (message.getType() == Message.TYPE_PRIVATE_FILE) {
                message.setType(Message.TYPE_PRIVATE);
            } else if (message.isFileOrImage()) {
                message.setType(Message.TYPE_TEXT);
            }
            message.setOob(true);
            message.setDeleted(false);
            mXmppConnectionService.updateMessage(message);
        }
        this.message.setTransferable(this);
        try {
            final Message.FileParams fileParams = message.getFileParams();
            if (message.hasFileOnRemoteHost()) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else if (message.isOOb() && fileParams.url != null && fileParams.size > 0) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else {
                mUrl = AesGcmURL.of(message.getBody().split("\n")[0]);
            }
            final AbstractConnectionManager.Extension extension = AbstractConnectionManager.Extension.of(mUrl.encodedPath());
            if (VALID_CRYPTO_EXTENSIONS.contains(extension.main)) {
                this.message.setEncryption(Message.ENCRYPTION_PGP);
            } else if (message.getEncryption() != Message.ENCRYPTION_OTR
                    && message.getEncryption() != Message.ENCRYPTION_AXOLOTL) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            final String ext = extension.getExtension();
            if (ext != null) {
                message.setRelativeFilePath(String.format("%s.%s", message.getUuid(), ext));
            } else if (Strings.isNullOrEmpty(message.getRelativeFilePath())) {
                message.setRelativeFilePath(message.getUuid());
            }
            setupFile();
            if (this.message.getEncryption() == Message.ENCRYPTION_AXOLOTL && this.file.getKey() == null) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            //TODO add auth tag size to knownFileSize
            final long knownFileSize = message.getFileParams().size;
            if (knownFileSize > 0 && interactive) {
                this.file.setExpectedSize(knownFileSize);
                download(true);
            } else {
                checkFileSize(interactive);
            }
        } catch (final IllegalArgumentException e) {
            this.cancel();
        }
    }

    private void setupFile() {
        final String reference = mUrl.fragment();
        if (reference != null && AesGcmURL.IV_KEY.matcher(reference).matches()) {
            this.file = new DownloadableFile(mXmppConnectionService.getCacheDir().getAbsolutePath() + "/" + message.getUuid());
            this.file.setKeyAndIv(CryptoHelper.hexToBytes(reference));
            Log.d(Config.LOGTAG, "create temporary OMEMO encrypted file: " + this.file.getAbsolutePath() + "(" + message.getMimeType() + ")");
        } else {
            this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
        }
    }

    private void download(final boolean interactive) {
        EXECUTOR.execute(new FileDownloader(interactive));
    }

    private void checkFileSize(final boolean interactive) {
        EXECUTOR.execute(new FileSizeChecker(interactive));
    }

    @Override
    public void cancel() {
        this.canceled = true;
        final Call call = this.mostRecentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        mHttpConnectionManager.finishConnection(this);
        message.setTransferable(null);
        if (message.isFileOrImage()) {
            message.setDeleted(true);
        }
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void decryptFile() throws IOException {
        final DownloadableFile outputFile = mXmppConnectionService.getFileBackend().getFile(message, true);

        if (outputFile.getParentFile().mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directories for " + outputFile.getAbsolutePath());
        }

        if (!outputFile.createNewFile()) {
            Log.w(Config.LOGTAG, "unable to create output file " + outputFile.getAbsolutePath());
        }

        final InputStream is = new FileInputStream(this.file);

        outputFile.setKey(this.file.getKey());
        outputFile.setIv(this.file.getIv());
        final OutputStream os = AbstractConnectionManager.createOutputStream(outputFile, false, true);

        ByteStreams.copy(is, os);

        FileBackend.close(is);
        FileBackend.close(os);

        if (!file.delete()) {
            Log.w(Config.LOGTAG, "unable to delete temporary OMEMO encrypted file " + file.getAbsolutePath());
        }
    }

    private void finish() {
        message.setTransferable(null);
        mHttpConnectionManager.finishConnection(this);
        boolean notify = acceptedAutomatically && !message.isRead();
        if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            notify = message.getConversation().getAccount().getPgpDecryptionService().decrypt(message, notify);
        }
        mHttpConnectionManager.updateConversationUi(true);
        final boolean notifyAfterScan = notify;
        final DownloadableFile file = mXmppConnectionService.getFileBackend().getFile(message, true);
        mXmppConnectionService.getFileBackend().updateMediaScanner(file, () -> {
            if (notifyAfterScan) {
                mXmppConnectionService.getNotificationService().push(message);
            }
        });
    }

    private void decryptIfNeeded() throws IOException {
        if (file.getKey() != null && file.getIv() != null) {
            decryptFile();
        }
    }

    private void changeStatus(int status) {
        this.mStatus = status;
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void showToastForException(Exception e) {
        if (e instanceof java.net.UnknownHostException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_server_not_found);
        } else if (e instanceof java.net.ConnectException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_connect);
        } else if (e instanceof FileWriterException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_write_file);
        } else if (!(e instanceof CancellationException)) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_file_not_found);
        }
    }

    private void updateProgress(long i) {
        this.mProgress = (int) i;
        mHttpConnectionManager.updateConversationUi(false);
    }

    @Override
    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public long getFileSize() {
        if (this.file != null) {
            return this.file.getExpectedSize();
        } else {
            return 0;
        }
    }

    @Override
    public int getProgress() {
        return this.mProgress;
    }

    public Message getMessage() {
        return message;
    }

    private class FileSizeChecker implements Runnable {

        private final boolean interactive;

        FileSizeChecker(boolean interactive) {
            this.interactive = interactive;
        }


        @Override
        public void run() {
            check();
        }

        private void retrieveFailed(@Nullable Exception e) {
            changeStatus(STATUS_OFFER_CHECK_FILESIZE);
            if (interactive) {
                if (e != null) {
                    showToastForException(e);
                }
            } else {
                HttpDownloadConnection.this.acceptedAutomatically = false;
                HttpDownloadConnection.this.mXmppConnectionService.getNotificationService().push(message);
            }
            cancel();
        }

        private void check() {
            long size;
            try {
                size = retrieveFileSize();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "io exception in http file size checker: " + e.getMessage());
                retrieveFailed(e);
                return;
            }
            final Message.FileParams fileParams = message.getFileParams();
            FileBackend.updateFileParams(message, fileParams.url, size);
            message.setOob(true);
            mXmppConnectionService.databaseBackend.updateMessage(message, true);
            file.setExpectedSize(size);
            message.resetFileParams();
            if (mHttpConnectionManager.hasStoragePermission()
                    && size <= mHttpConnectionManager.getAutoAcceptFileSize()
                    && mXmppConnectionService.isDataSaverDisabled()) {
                HttpDownloadConnection.this.acceptedAutomatically = true;
                download(interactive);
            } else {
                changeStatus(STATUS_OFFER);
                HttpDownloadConnection.this.acceptedAutomatically = false;
                HttpDownloadConnection.this.mXmppConnectionService.getNotificationService().push(message);
            }
        }

        private long retrieveFileSize() throws IOException {
            final OkHttpClient client = mHttpConnectionManager.buildHttpClient(
                    mUrl,
                    message.getConversation().getAccount(),
                    interactive
            );
            final Request request = new Request.Builder()
                    .url(URL.stripFragment(mUrl))
                    .head()
                    .build();
            mostRecentCall = client.newCall(request);
            try {
                final Response response = mostRecentCall.execute();
                final String contentLength = response.header("Content-Length");
                final String contentType = response.header("Content-Type");
                final AbstractConnectionManager.Extension extension = AbstractConnectionManager.Extension.of(mUrl.encodedPath());
                if (Strings.isNullOrEmpty(extension.getExtension()) && contentType != null) {
                    final String fileExtension = MimeUtils.guessExtensionFromMimeType(contentType);
                    if (fileExtension != null) {
                        message.setRelativeFilePath(String.format("%s.%s", message.getUuid(), fileExtension));
                        Log.d(Config.LOGTAG, "rewriting name after not finding extension in url but in content type");
                        setupFile();
                    }
                }
                if (Strings.isNullOrEmpty(contentLength)) {
                    throw new IOException("no content-length found in HEAD response");
                }
                return Long.parseLong(contentLength, 10);
            } catch (IOException e) {
                Log.d(Config.LOGTAG, "io exception during HEAD " + e.getMessage());
                throw e;
            } catch (NumberFormatException e) {
                throw new IOException();
            }
        }

    }

    private class FileDownloader implements Runnable {

        private final boolean interactive;

        public FileDownloader(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            try {
                changeStatus(STATUS_DOWNLOADING);
                download();
                decryptIfNeeded();
                updateImageBounds();
                finish();
            } catch (final SSLHandshakeException e) {
                changeStatus(STATUS_OFFER);
            } catch (final Exception e) {
                Log.d(Config.LOGTAG,"problem downloading",e);
                if (interactive) {
                    showToastForException(e);
                } else {
                    HttpDownloadConnection.this.acceptedAutomatically = false;
                    HttpDownloadConnection.this.mXmppConnectionService.getNotificationService().push(message);
                }
                cancel();
            }
        }

        private void download() throws Exception {
            final OkHttpClient client = mHttpConnectionManager.buildHttpClient(
                    mUrl,
                    message.getConversation().getAccount(),
                    interactive
            );

            final Request.Builder requestBuilder = new Request.Builder().url(URL.stripFragment(mUrl));

            final long expected = file.getExpectedSize();
            final boolean tryResume = file.exists() && file.getSize() > 0 && file.getSize() < expected;
            final long resumeSize;
            if (tryResume) {
                resumeSize = file.getSize();
                Log.d(Config.LOGTAG, "http download trying resume after " + resumeSize + " of " + expected);
                requestBuilder.addHeader("Range", String.format(Locale.ENGLISH, "bytes=%d-", resumeSize));
            } else {
                resumeSize = 0;
            }
            final Request request = requestBuilder.build();
            mostRecentCall = client.newCall(request);
            final Response response = mostRecentCall.execute();
            final int code = response.code();
            if (code >= 200 && code <= 299) {
                final String contentRange = response.header("Content-Range");
                final boolean serverResumed = tryResume && contentRange != null && contentRange.startsWith("bytes " + resumeSize + "-");
                final InputStream inputStream = response.body().byteStream();
                final OutputStream outputStream;
                long transmitted = 0;
                if (tryResume && serverResumed) {
                    Log.d(Config.LOGTAG, "server resumed");
                    transmitted = file.getSize();
                    updateProgress(Math.round(((double) transmitted / expected) * 100));
                    outputStream = AbstractConnectionManager.createOutputStream(file, true, false);
                } else {
                    final String contentLength = response.header("Content-Length");
                    final long size = Strings.isNullOrEmpty(contentLength) ? 0 : Longs.tryParse(contentLength);
                    if (expected != size) {
                        Log.d(Config.LOGTAG, "content-length reported on GET (" + size + ") did not match Content-Length reported on HEAD (" + expected + ")");
                    }
                    file.getParentFile().mkdirs();
                    if (!file.exists() && !file.createNewFile()) {
                        throw new FileWriterException();
                    }
                    outputStream = AbstractConnectionManager.createOutputStream(file, false, false);
                }
                int count;
                byte[] buffer = new byte[4096];
                while ((count = inputStream.read(buffer)) != -1) {
                    transmitted += count;
                    try {
                        outputStream.write(buffer, 0, count);
                    } catch (IOException e) {
                        throw new FileWriterException();
                    }
                    updateProgress(Math.round(((double) transmitted / expected) * 100));
                    if (canceled) {
                        throw new CancellationException();
                    }
                }
                outputStream.flush();
            } else {
                throw new IOException(String.format(Locale.ENGLISH, "HTTP Status code was %d", code));
            }
        }

        private void updateImageBounds() {
            final boolean privateMessage = message.isPrivateMessage();
            message.setType(privateMessage ? Message.TYPE_PRIVATE_FILE : Message.TYPE_FILE);
            final String url;
            final String ref = mUrl.fragment();
            if (ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()) {
                url = AesGcmURL.toAesGcmUrl(mUrl);
            } else {
                url = mUrl.toString();
            }
            mXmppConnectionService.getFileBackend().updateFileParams(message, url);
            mXmppConnectionService.updateMessage(message);
        }

    }
}
