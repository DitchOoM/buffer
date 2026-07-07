package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer

/*
 * JS EC point decompression. WebCrypto offers no decompression (`importKey('raw')` requires the
 * uncompressed point), so the curve sqrt is computed in the host BigInt inside the js(...) literal
 * below — Kotlin variables are referenced directly (no IIFE-shadowing param), matching the WebCrypto
 * glue. Public-coordinate-only math, so a variable-time BigInt leaks nothing.
 */

actual fun ecPublicKeyDecompress(
    curve: KeyAgreementCurve,
    compressedPoint: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val cp = requireCompressedPoint(curve, compressedPoint)
    val xHex = readFieldHex(compressedPoint, cp.xStart, cp.field)
    val uncompressedHex = jsDecompressHex(ecPrimeHex(curve), ecBHex(curve), xHex, if (cp.wantOdd) 1 else 0)
    if (uncompressedHex.isEmpty()) failPointNotOnCurve() // x had no square root => not on the curve
    return hexToReadBuffer(uncompressedHex, factory)
}

// Recovers Y from (p, b, x) and parity using the host BigInt; returns "04"+x+y hex, or "" if x has no
// square root (off-curve). p ≡ 3 (mod 4) so sqrt(v) = v^((p+1)/4) mod p (a single modular exponentiation).
@Suppress("UnusedParameter") // pHex/bHex/xHex/wantOdd are referenced inside the js(...) template
private fun jsDecompressHex(
    pHex: String,
    bHex: String,
    xHex: String,
    wantOdd: Int,
): String =
    js(
        """
        (function(){
          var p=BigInt('0x'+pHex), b=BigInt('0x'+bHex), x=BigInt('0x'+xHex);
          if(x>=p) return ''; // X must be a reduced field element (matches the native stacks)
          var ONE=BigInt(1), ZERO=BigInt(0), THREE=BigInt(3);
          function mod(a,m){ var r=a%m; return r<ZERO ? r+m : r; }
          function mpow(base,exp,m){ base=mod(base,m); var r=ONE; while(exp>ZERO){ if(exp&ONE)r=mod(r*base,m); base=mod(base*base,m); exp>>=ONE; } return r; }
          var rhs=mod(mpow(x,THREE,p) - THREE*x + b, p);
          var y=mpow(rhs, (p+ONE)>>BigInt(2), p);
          if(mod(y*y,p) !== rhs) return '';
          if((y&ONE) !== BigInt(wantOdd)) y=p-y;
          var s=y.toString(16); while(s.length<xHex.length) s='0'+s;
          return '04'+xHex+s;
        })()
        """,
    ) as String
