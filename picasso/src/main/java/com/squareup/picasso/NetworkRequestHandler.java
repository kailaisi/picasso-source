/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.picasso;

import android.net.NetworkInfo;
import java.io.IOException;
import okhttp3.CacheControl;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.squareup.picasso.Picasso.LoadedFrom.DISK;
import static com.squareup.picasso.Picasso.LoadedFrom.NETWORK;

class NetworkRequestHandler extends RequestHandler {
  private static final String SCHEME_HTTP = "http";
  private static final String SCHEME_HTTPS = "https";

  private final Downloader downloader;
  private final Stats stats;

  NetworkRequestHandler(Downloader downloader, Stats stats) {
    this.downloader = downloader;
    this.stats = stats;
  }

  @Override public boolean canHandleRequest(Request data) {
    String scheme = data.uri.getScheme();
    return (SCHEME_HTTP.equals(scheme) || SCHEME_HTTPS.equals(scheme));
  }

  @Override public Result load(Request request, int networkPolicy) throws IOException {
    //创建okhttp3的请求，里面根据设置的缓存策略进行了缓存的处理。从sdcard的缓存处理都是通过okhttp来进行了处理，而没有单独进行处理
    okhttp3.Request downloaderRequest = createRequest(request, networkPolicy);
    //发起请求
    Response response = downloader.load(downloaderRequest);
    ResponseBody body = response.body();

    if (!response.isSuccessful()) {//加载失败
      body.close();
      throw new ResponseException(response.code(), request.networkPolicy);
    }

    // Cache response is only null when the response comes fully from the network. Both completely
    // cached and conditionally cached responses will have a non-null cache response.
    //因为支持okhttp缓存模式，所以这里根据是否命中了缓存，来进行Result相关参数的设置
    Picasso.LoadedFrom loadedFrom = response.cacheResponse() == null ? NETWORK : DISK;

    // Sometimes response content length is zero when requests are being replayed. Haven't found
    // root cause to this but retrying the request seems safe to do so.
    if (loadedFrom == DISK && body.contentLength() == 0) {
      body.close();
      throw new ContentLengthException("Received response with 0 content-length header.");
    }
    if (loadedFrom == NETWORK && body.contentLength() > 0) {
      stats.dispatchDownloadFinished(body.contentLength());
    }
    return new Result(body.source(), loadedFrom);
  }

  @Override int getRetryCount() {
    return 2;
  }

  @Override boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
    return info == null || info.isConnected();
  }

  @Override boolean supportsReplay() {
    return true;
  }
  /**
   * Return the stable ID for the item at <code>position</code>. If {@link #hasStableIds()}
   * would return false this method should return {@link #NO_ID}. The default implementation
   * of this method returns {@link #NO_ID}.
   *
   * @param position Adapter position to query
   * @return the stable ID of the item at position
   */
  private static okhttp3.Request createRequest(Request request, int networkPolicy) {
    CacheControl cacheControl = null;
    if (networkPolicy != 0) {//使用CacheControl来进行缓存处理
      if (NetworkPolicy.isOfflineOnly(networkPolicy)) {
        cacheControl = CacheControl.FORCE_CACHE;
      } else {
        CacheControl.Builder builder = new CacheControl.Builder();
        if (!NetworkPolicy.shouldReadFromDiskCache(networkPolicy)) {
          //如果设置不读缓存，则直接设置非缓存
          builder.noCache();
        }
        if (!NetworkPolicy.shouldWriteToDiskCache(networkPolicy)) {
          //使用存储缓存
          builder.noStore();
        }
        cacheControl = builder.build();
      }
    }

    okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.uri.toString());
    if (cacheControl != null) {
      builder.cacheControl(cacheControl);
    }
    return builder.build();
  }

  static class ContentLengthException extends IOException {
    ContentLengthException(String message) {
      super(message);
    }
  }

  static final class ResponseException extends IOException {
    final int code;
    final int networkPolicy;

    ResponseException(int code, int networkPolicy) {
      super("HTTP " + code);
      this.code = code;
      this.networkPolicy = networkPolicy;
    }
  }
}
