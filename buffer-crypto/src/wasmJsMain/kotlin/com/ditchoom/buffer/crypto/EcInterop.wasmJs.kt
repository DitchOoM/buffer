@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import kotlin.js.ExperimentalWasmJsInterop

/*
 * wasmJs EC point decompression. Mirrors the JS actual but marshals through `JsString` via a `@JsFun`
 * external; the same host-BigInt curve sqrt runs underneath. WebCrypto can't decompress, so BigInt is
 * the portable basis; public-coordinate-only math leaks nothing.
 */

actual fun ecPublicKeyDecompress(
    curve: KeyAgreementCurve,
    compressedPoint: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val cp = requireCompressedPoint(curve, compressedPoint)
    val xHex = readFieldHex(compressedPoint, cp.xStart, cp.field)
    val uncompressedHex = jsDecompressHex(ecPrimeHex(curve), ecBHex(curve), xHex, if (cp.wantOdd) 1 else 0).toString()
    if (uncompressedHex.isEmpty()) failPointNotOnCurve() // x had no square root => not on the curve
    return hexToReadBuffer(uncompressedHex, factory)
}

// p ≡ 3 (mod 4) so sqrt(v) = v^((p+1)/4) mod p; returns "04"+x+y hex, or "" if x is off-curve.
@JsFun(
    """(pHex, bHex, xHex, wantOdd) => {
      var p=BigInt('0x'+pHex), b=BigInt('0x'+bHex), x=BigInt('0x'+xHex);
      var ONE=BigInt(1), ZERO=BigInt(0), THREE=BigInt(3);
      function mod(a,m){ var r=a%m; return r<ZERO ? r+m : r; }
      function mpow(base,exp,m){ base=mod(base,m); var r=ONE; while(exp>ZERO){ if(exp&ONE)r=mod(r*base,m); base=mod(base*base,m); exp>>=ONE; } return r; }
      var rhs=mod(mpow(x,THREE,p) - THREE*x + b, p);
      var y=mpow(rhs, (p+ONE)>>BigInt(2), p);
      if(mod(y*y,p) !== rhs) return '';
      if((y&ONE) !== BigInt(wantOdd)) y=p-y;
      var s=y.toString(16); while(s.length<xHex.length) s='0'+s;
      return '04'+xHex+s;
    }""",
)
private external fun jsDecompressHex(
    pHex: String,
    bHex: String,
    xHex: String,
    wantOdd: Int,
): JsString
