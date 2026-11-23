@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/**
 * wasmJs IndexedDB glue for the persistent [WebCryptoKeyStore]. Mirrors the JS actual: non-extractable
 * `CryptoKey`s are stored as `{ meta, key }` objects in object store `k` keyed by alias, and
 * re-registered into the shared `globalThis.__bcNE` token registry on load. Each `@JsFun` body is
 * self-contained (the registry + IndexedDB open helper are inlined) and marshals strings through
 * `JsString`. Runtime-exercised in a browser; Node has no IndexedDB, so this path is CI/browser-gated.
 */

internal actual val webCryptoIndexedDbAvailable: Boolean
    get() = jsIndexedDbAvailable()

internal actual suspend fun webCryptoIdbPut(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
) {
    jsIdbPut(dbName.toJsString(), alias.toJsString(), token, meta.toJsString()).await<JsString>()
}

internal actual suspend fun webCryptoIdbLoad(
    dbName: String,
    alias: String,
): String? = jsIdbLoad(dbName.toJsString(), alias.toJsString()).await<JsString?>()?.toString()

internal actual suspend fun webCryptoIdbDelete(
    dbName: String,
    alias: String,
): Boolean = jsIdbDelete(dbName.toJsString(), alias.toJsString()).await<JsBoolean>().toBoolean()

internal actual suspend fun webCryptoIdbContains(
    dbName: String,
    alias: String,
): Boolean = jsIdbContains(dbName.toJsString(), alias.toJsString()).await<JsBoolean>().toBoolean()

internal actual suspend fun webCryptoIdbAliases(dbName: String): String = jsIdbAliases(dbName.toJsString()).await<JsString>().toString()

// --- externals ---------------------------------------------------------------

@JsFun(
    """() => (typeof globalThis.indexedDB !== 'undefined' && globalThis.indexedDB !== null)""",
)
private external fun jsIndexedDbAvailable(): Boolean

@JsFun(
    """(dbName, alias, token, meta) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function od(n) { return new Promise(function (res, rej) { var r = globalThis.indexedDB.open(n, 1);
        r.onupgradeneeded = function () { r.result.createObjectStore('k'); };
        r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; }); }
      var db = await od(dbName);
      await new Promise(function (res, rej) { var tx = db.transaction('k', 'readwrite');
        tx.objectStore('k').put({ meta: meta, key: reg.m.get(token) }, alias);
        tx.oncomplete = function () { res(0); }; tx.onerror = function () { rej(tx.error); };
        tx.onabort = function () { rej(tx.error); }; });
      db.close();
      return '';
    })()""",
)
private external fun jsIdbPut(
    dbName: JsString,
    alias: JsString,
    token: Int,
    meta: JsString,
): Promise<JsString>

@JsFun(
    """(dbName, alias) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function od(n) { return new Promise(function (res, rej) { var r = globalThis.indexedDB.open(n, 1);
        r.onupgradeneeded = function () { r.result.createObjectStore('k'); };
        r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; }); }
      var db = await od(dbName);
      var v = await new Promise(function (res, rej) { var tx = db.transaction('k', 'readonly');
        var q = tx.objectStore('k').get(alias); q.onsuccess = function () { res(q.result); };
        q.onerror = function () { rej(q.error); }; });
      db.close();
      if (v === undefined || v === null) return null;
      var t = reg.n++; reg.m.set(t, v.key);
      return String(t) + ':' + v.meta;
    })()""",
)
private external fun jsIdbLoad(
    dbName: JsString,
    alias: JsString,
): Promise<JsString?>

@JsFun(
    """(dbName, alias) => (async () => {
      function od(n) { return new Promise(function (res, rej) { var r = globalThis.indexedDB.open(n, 1);
        r.onupgradeneeded = function () { r.result.createObjectStore('k'); };
        r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; }); }
      var db = await od(dbName);
      var existed = await new Promise(function (res, rej) { var e = false; var tx = db.transaction('k', 'readwrite');
        var st = tx.objectStore('k'); var c = st.count(alias);
        c.onsuccess = function () { e = (c.result > 0); if (e) { st.delete(alias); } };
        tx.oncomplete = function () { res(e); }; tx.onerror = function () { rej(tx.error); }; });
      db.close();
      return existed;
    })()""",
)
private external fun jsIdbDelete(
    dbName: JsString,
    alias: JsString,
): Promise<JsBoolean>

@JsFun(
    """(dbName, alias) => (async () => {
      function od(n) { return new Promise(function (res, rej) { var r = globalThis.indexedDB.open(n, 1);
        r.onupgradeneeded = function () { r.result.createObjectStore('k'); };
        r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; }); }
      var db = await od(dbName);
      var found = await new Promise(function (res, rej) { var tx = db.transaction('k', 'readonly');
        var c = tx.objectStore('k').count(alias); c.onsuccess = function () { res(c.result > 0); };
        c.onerror = function () { rej(c.error); }; });
      db.close();
      return found;
    })()""",
)
private external fun jsIdbContains(
    dbName: JsString,
    alias: JsString,
): Promise<JsBoolean>

@JsFun(
    """(dbName) => (async () => {
      function od(n) { return new Promise(function (res, rej) { var r = globalThis.indexedDB.open(n, 1);
        r.onupgradeneeded = function () { r.result.createObjectStore('k'); };
        r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; }); }
      var db = await od(dbName);
      var keys = await new Promise(function (res, rej) { var tx = db.transaction('k', 'readonly');
        var q = tx.objectStore('k').getAllKeys(); q.onsuccess = function () { res(q.result); };
        q.onerror = function () { rej(q.error); }; });
      db.close();
      return keys.join('\n');
    })()""",
)
private external fun jsIdbAliases(dbName: JsString): Promise<JsString>
