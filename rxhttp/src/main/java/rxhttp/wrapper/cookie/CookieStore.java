package rxhttp.wrapper.cookie;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.annotations.Nullable;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.internal.Util;
import okhttp3.internal.cache.DiskLruCache;
import okhttp3.internal.io.FileSystem;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Source;

/**
 * User: ljx
 * Date: 2019-12-29
 * Time: 21:51
 */
public class CookieStore implements ICookieJar {

    private static final int appVersion = 1;

    private DiskLruCache diskCache; //磁盘缓存
    private Map<String, List<Cookie>> memoryCache; //内存缓存

    public CookieStore() {
        this(null, Integer.MAX_VALUE, true);
    }

    public CookieStore(@Nullable File directory) {
        this(directory, Integer.MAX_VALUE, true);
    }

    public CookieStore(@Nullable File directory, boolean enabledMemory) {
        this(directory, Integer.MAX_VALUE, enabledMemory);
    }

    /**
     * 配置cookie 存储策略，注意：内存缓存、磁盘缓存至少要开启一个，否则抛出非法参数异常
     *
     * @param directory     磁盘缓存目录，传入null，则代表不开启磁盘缓存
     * @param maxSize       磁盘缓存最大size，默认为 Integer.MAX_VALUE
     * @param enabledMemory 是否开启内存缓存
     */
    public CookieStore(@Nullable File directory, long maxSize, boolean enabledMemory) {
        if (!enabledMemory && directory == null)
            throw new IllegalArgumentException("Memory or disk caching must be enabled");
        if (enabledMemory) {
            memoryCache = new ConcurrentHashMap<>();
        }
        if (directory != null) {
            diskCache = DiskLruCache.create(FileSystem.SYSTEM, directory, appVersion, 1, maxSize);
        }
    }

    private static String md5(String key) {
        return ByteString.encodeUtf8(key).md5().hex();
    }

    @Override
    public void saveCookie(HttpUrl url, List<Cookie> cookies) {
        final String host = url.host();
        if (memoryCache != null) { //开启了内存缓存，则将cookie写入内存
            memoryCache.put(host, cookies);
        }

        if (diskCache != null) { //开启了磁盘缓存，则将cookie写入磁盘
            DiskLruCache.Editor editor = null;
            try {
                editor = diskCache.edit(md5(host));
                if (editor == null) {
                    return;
                }
                writeCookie(editor, cookies);
                editor.commit();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                abortQuietly(editor);
            }
        }
    }

    @Override
    public void saveCookie(HttpUrl url, Cookie cookie) {
        List<Cookie> cookies = new ArrayList<>();
        saveCookie(url, cookies);
    }

    @Override
    public List<Cookie> loadCookie(HttpUrl url) {
        final String host = url.host();
        List<Cookie> cookies;
        if (memoryCache != null) {  //1、开启了内存缓存，则从内存查找cookie
            cookies = memoryCache.get(host);
            if (cookies != null) { //2、内存缓存查找成功，直接返回
                return cookies;
            }
        }
        cookies = new ArrayList<>();
        if (diskCache != null) { //3、开启了磁盘缓存，则从磁盘查找cookie
            DiskLruCache.Snapshot snapshot = null;
            try {
                //4、磁盘缓存查找
                snapshot = diskCache.get(md5(host));
                if (snapshot == null) return cookies;
                List<Cookie> cookiesList = readCookie(url, snapshot.getSource(0));
                if (!cookiesList.isEmpty())
                    cookies.addAll(cookiesList);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Util.closeQuietly(snapshot);
            }
        }
        if (!cookies.isEmpty()) //5、磁盘缓存查找成功，添加进内存缓存
            memoryCache.put(host, cookies);
        return cookies;
    }

    @Override
    public void removeCookie(HttpUrl url) {
        String host = url.host();
        if (memoryCache != null) {
            memoryCache.remove(host);
        }
        if (diskCache != null) {
            try {
                diskCache.remove(md5(host));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void removeAllCookie() {
        if (memoryCache != null)
            memoryCache.clear();
        if (diskCache != null) {
            try {
                diskCache.evictAll();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //从磁盘都cookie
    private List<Cookie> readCookie(HttpUrl url, Source in) throws IOException {
        List<Cookie> cookies = new ArrayList<>();
        try {
            BufferedSource source = Okio.buffer(in);
            int size = source.readInt();
            for (int i = 0; i < size; i++) {
                String name = source.readUtf8LineStrict();
                cookies.add(Cookie.parse(url, name));
            }
        } finally {
            in.close();
        }
        return cookies;
    }

    //cookie写入磁盘
    private void writeCookie(DiskLruCache.Editor editor, List<Cookie> cookies) throws
        IOException {
        BufferedSink sink = Okio.buffer(editor.newSink(0));
        sink.writeInt(cookies.size());
        for (Cookie cookie : cookies) {
            sink.writeUtf8(cookie.toString()).writeByte('\n');
        }
        sink.close();
    }

    private void abortQuietly(@Nullable DiskLruCache.Editor editor) {
        try {
            if (editor != null) {
                editor.abort();
            }
        } catch (Exception ignored) {
        }
    }
}
