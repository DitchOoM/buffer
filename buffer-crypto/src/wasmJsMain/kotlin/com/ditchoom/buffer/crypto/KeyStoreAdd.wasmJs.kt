@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

internal actual suspend fun webCryptoIdbAdd(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
): Boolean = jsIdbAdd(dbName.toJsString(), alias.toJsString(), token, meta.toJsString()).await<JsBoolean>().toBoolean()

// `add` fails with ConstraintError on an occupied alias; preventDefault keeps that from aborting the
// transaction, so "taken" is an answer rather than an error.
@JsFun(
    """(dbName, alias, token, meta) => (async () => {
      var g = globalThis; if (!g.__bcNE) { g.__bcNE = { m: new Map(), n: 1 }; } var reg = g.__bcNE;
      function od(n) { return new Promise(function (res, rej) { var r = globalThis.indexedDB.open(n, 1);
        r.onupgradeneeded = function () { r.result.createObjectStore('k'); };
        r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; }); }
      var db = await od(dbName);
      try {
        return await new Promise(function (res, rej) { var taken = false; var tx = db.transaction('k', 'readwrite');
          var rq = tx.objectStore('k').add({ meta: meta, key: reg.m.get(token) }, alias);
          rq.onerror = function (ev) {
            if (rq.error && rq.error.name === 'ConstraintError') { taken = true; ev.preventDefault(); ev.stopPropagation(); } };
          tx.oncomplete = function () { res(!taken); };
          tx.onerror = function () { if (!taken) { rej(tx.error); } };
          tx.onabort = function () { rej(tx.error); }; });
      } finally { db.close(); }
    })()""",
)
private external fun jsIdbAdd(
    dbName: JsString,
    alias: JsString,
    token: Int,
    meta: JsString,
): Promise<JsBoolean>
