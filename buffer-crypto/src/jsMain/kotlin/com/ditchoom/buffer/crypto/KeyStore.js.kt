package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.Promise

/*
 * JS IndexedDB glue for the persistent WebCryptoKeyStore. Non-extractable CryptoKeys are stored as
 * objects (structured clone preserves extractable:false) in an object store 'k', keyed by alias, as
 * `{ meta, key }`. On load the key is re-registered into the same `globalThis.__bcNE` token registry
 * the provider uses, so only strings/ints cross the boundary. The js(...) bodies use `.then()` chains
 * (Kotlin/JS forbids async/await in js("…")) and reference Kotlin params directly.
 */

// Registry accessor + an inline IndexedDB open helper, inlined into each literal (a shared js()
// function cannot be referenced by name from another js() template). Store name is 'k'.
private const val REG = "var g=globalThis; if(!g.__bcNE){ g.__bcNE={m:new Map(),n:1}; } var reg=g.__bcNE;"
private const val OPENDB =
    "function od(n){ return new Promise(function(res,rej){ var r=globalThis.indexedDB.open(n,1); " +
        "r.onupgradeneeded=function(){ r.result.createObjectStore('k'); }; " +
        "r.onsuccess=function(){ res(r.result); }; r.onerror=function(){ rej(r.error); }; }); }"

internal actual val webCryptoIndexedDbAvailable: Boolean
    get() = js("(typeof globalThis.indexedDB !== 'undefined' && globalThis.indexedDB !== null)") as Boolean

internal actual suspend fun webCryptoIdbPut(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
) {
    jsIdbPut(dbName, alias, token, meta).await()
}

internal actual suspend fun webCryptoIdbLoad(
    dbName: String,
    alias: String,
): String? = jsIdbLoad(dbName, alias).await()

internal actual suspend fun webCryptoIdbDelete(
    dbName: String,
    alias: String,
): Boolean = jsIdbDelete(dbName, alias).await()

internal actual suspend fun webCryptoIdbContains(
    dbName: String,
    alias: String,
): Boolean = jsIdbContains(dbName, alias).await()

internal actual suspend fun webCryptoIdbAliases(dbName: String): String = jsIdbAliases(dbName).await()

// --- raw JS helpers ----------------------------------------------------------

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsIdbPut(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
): Promise<String> =
    js(
        """
        (function(){
          $REG $OPENDB
          return od(dbName).then(function(db){
            return new Promise(function(res,rej){
              var tx=db.transaction('k','readwrite');
              tx.objectStore('k').put({ meta: meta, key: reg.m.get(token) }, alias);
              tx.oncomplete=function(){ db.close(); res(''); };
              tx.onerror=function(){ db.close(); rej(tx.error); };
              tx.onabort=function(){ db.close(); rej(tx.error); };
            });
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsIdbLoad(
    dbName: String,
    alias: String,
): Promise<String?> =
    js(
        """
        (function(){
          $REG $OPENDB
          return od(dbName).then(function(db){
            return new Promise(function(res,rej){
              var tx=db.transaction('k','readonly');
              var q=tx.objectStore('k').get(alias);
              q.onsuccess=function(){ var v=q.result; db.close();
                if(v===undefined||v===null){ res(null); return; }
                var t=reg.n++; reg.m.set(t, v.key); res(String(t)+':'+v.meta); };
              q.onerror=function(){ db.close(); rej(q.error); };
            });
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsIdbDelete(
    dbName: String,
    alias: String,
): Promise<Boolean> =
    js(
        """
        (function(){
          $OPENDB
          return od(dbName).then(function(db){
            return new Promise(function(res,rej){
              var existed=false;
              var tx=db.transaction('k','readwrite');
              var st=tx.objectStore('k');
              var c=st.count(alias);
              c.onsuccess=function(){ existed=(c.result>0); if(existed){ st.delete(alias); } };
              tx.oncomplete=function(){ db.close(); res(existed); };
              tx.onerror=function(){ db.close(); rej(tx.error); };
            });
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsIdbContains(
    dbName: String,
    alias: String,
): Promise<Boolean> =
    js(
        """
        (function(){
          $OPENDB
          return od(dbName).then(function(db){
            return new Promise(function(res,rej){
              var tx=db.transaction('k','readonly');
              var c=tx.objectStore('k').count(alias);
              c.onsuccess=function(){ db.close(); res(c.result>0); };
              c.onerror=function(){ db.close(); rej(c.error); };
            });
          });
        })()
        """,
    )

@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsIdbAliases(dbName: String): Promise<String> =
    js(
        """
        (function(){
          $OPENDB
          return od(dbName).then(function(db){
            return new Promise(function(res,rej){
              var tx=db.transaction('k','readonly');
              var q=tx.objectStore('k').getAllKeys();
              q.onsuccess=function(){ db.close(); res(q.result.join('\n')); };
              q.onerror=function(){ db.close(); rej(q.error); };
            });
          });
        })()
        """,
    )
