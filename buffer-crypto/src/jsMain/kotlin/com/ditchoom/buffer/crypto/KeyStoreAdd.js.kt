package com.ditchoom.buffer.crypto

import kotlinx.coroutines.await
import kotlin.js.Promise

internal actual suspend fun webCryptoIdbAdd(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
): Boolean = jsIdbAdd(dbName, alias, token, meta).await()

// `add` fails with ConstraintError on an occupied alias; preventDefault keeps that from aborting the
// transaction, so "taken" is an answer rather than an error.
@Suppress("UnusedParameter") // referenced inside the js(...) template
private fun jsIdbAdd(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
): Promise<Boolean> =
    js(
        """
        (function(){
          $IDB_REG $IDB_OPENDB
          return od(dbName).then(function(db){
            return new Promise(function(res,rej){
              var taken=false;
              var tx=db.transaction('k','readwrite');
              var rq=tx.objectStore('k').add({ meta: meta, key: reg.m.get(token) }, alias);
              rq.onerror=function(ev){
                if(rq.error && rq.error.name==='ConstraintError'){ taken=true; ev.preventDefault(); ev.stopPropagation(); }
              };
              tx.oncomplete=function(){ db.close(); res(!taken); };
              tx.onerror=function(){ if(!taken){ db.close(); rej(tx.error); } };
              tx.onabort=function(){ db.close(); rej(tx.error); };
            });
          });
        })()
        """,
    )
