import React, { useState, useEffect, useCallback } from 'react';
import styles from './styles.module.css';

interface BufferState {
  capacity: number;
  position: number;
  limit: number;
  data: (number | null)[];
  mode: 'write' | 'read';
}

interface BufferVisualizerProps {
  /** Initial capacity of the buffer */
  capacity?: number;
  /** Initial position */
  initialPosition?: number;
  /** Initial limit */
  initialLimit?: number;
  /** Initial data as string, hex array, or byte array */
  initialData?: string | number[];
  /** Initial mode */
  initialMode?: 'write' | 'read';
  /** Show character representation instead of hex */
  showChars?: boolean;
  /** Title for the visualization */
  title?: string;
  /** Highlight specific indices */
  highlight?: number[];
  /** Label for highlighted section */
  highlightLabel?: string;
  /** Show a slice visualization (fromIndex to toIndex) */
  sliceFrom?: number;
  /** Slice end index */
  sliceTo?: number;
  /** Byte order label to display */
  byteOrder?: 'big' | 'little';
  /** Hide the mode badge */
  hideMode?: boolean;
  /** Hide the remaining() display */
  hideRemaining?: boolean;
  /** Hide position/limit/capacity markers */
  hideMarkers?: boolean;
  /** Subtitle or description text */
  subtitle?: string;
  /** Start in interactive/editing mode */
  interactive?: boolean;
}

function stringToBytes(str: string): number[] {
  const encoder = new TextEncoder();
  return Array.from(encoder.encode(str));
}

function byteToChar(byte: number | null): string {
  if (byte === null) return '—';
  if (byte >= 32 && byte <= 126) {
    return String.fromCharCode(byte);
  }
  return '·';
}

export default function BufferVisualizer({
  capacity = 8,
  initialPosition = 0,
  initialLimit,
  initialData,
  initialMode = 'write',
  showChars = false,
  title,
  highlight = [],
  highlightLabel,
  sliceFrom,
  sliceTo,
  byteOrder,
  hideMode = false,
  hideRemaining = false,
  hideMarkers = false,
  subtitle,
  interactive = false,
}: BufferVisualizerProps): JSX.Element {
  const effectiveLimit = initialLimit ?? capacity;

  const getInitialData = useCallback((): (number | null)[] => {
    const data: (number | null)[] = Array(capacity).fill(null);
    if (initialData) {
      const bytes = typeof initialData === 'string'
        ? stringToBytes(initialData)
        : initialData;
      bytes.forEach((b, i) => {
        if (i < capacity) data[i] = b & 0xFF;
      });
    }
    return data;
  }, [capacity, initialData]);

  const getInitialState = useCallback((): BufferState => ({
    capacity,
    position: initialPosition,
    limit: effectiveLimit,
    data: getInitialData(),
    mode: initialMode,
  }), [capacity, initialPosition, effectiveLimit, getInitialData, initialMode]);

  const [state, setState] = useState<BufferState>(getInitialState);
  const [isEditing, setIsEditing] = useState(interactive);
  const [hasModified, setHasModified] = useState(false);

  const [log, setLog] = useState<string[]>([
    initialData
      ? `Buffer initialized with ${typeof initialData === 'string' ? `"${initialData}"` : 'data'}`
      : `Buffer allocated with capacity ${capacity}`
  ]);
  const [inputValue, setInputValue] = useState<string>('A');
  const [inputType, setInputType] = useState<'char' | 'byte' | 'string'>('char');

  // Reset state if props change
  useEffect(() => {
    setState(getInitialState());
    setHasModified(false);
  }, [getInitialState]);

  const addLog = (message: string) => {
    setLog(prev => [...prev.slice(-4), message]);
  };

  const enableEditing = () => {
    setIsEditing(true);
    addLog('Interactive mode enabled - try the controls below!');
  };

  const resetToDefault = () => {
    setState(getInitialState());
    setHasModified(false);
    setLog([
      initialData
        ? `Buffer reset to initial state`
        : `Buffer reset to initial state`
    ]);
  };

  const writeByte = (value: number) => {
    if (state.mode !== 'write') {
      addLog('❌ Cannot write in read mode');
      return;
    }
    if (state.position >= state.limit) {
      addLog('❌ Buffer overflow: position >= limit');
      return;
    }
    const byte = value & 0xFF;
    const newData = [...state.data];
    newData[state.position] = byte;
    setState(prev => ({
      ...prev,
      data: newData,
      position: prev.position + 1,
    }));
    setHasModified(true);
    const charRepr = byte >= 32 && byte <= 126 ? ` '${String.fromCharCode(byte)}'` : '';
    addLog(`writeByte(0x${byte.toString(16).toUpperCase().padStart(2, '0')}${charRepr}) → position=${state.position + 1}`);
  };

  const writeString = (str: string) => {
    if (state.mode !== 'write') {
      addLog('❌ Cannot write in read mode');
      return;
    }
    const bytes = stringToBytes(str);
    if (state.position + bytes.length > state.limit) {
      addLog(`❌ Buffer overflow: need ${bytes.length} bytes, have ${state.limit - state.position}`);
      return;
    }
    const newData = [...state.data];
    bytes.forEach((b, i) => {
      newData[state.position + i] = b;
    });
    setState(prev => ({
      ...prev,
      data: newData,
      position: prev.position + bytes.length,
    }));
    setHasModified(true);
    addLog(`writeString("${str}") → position=${state.position + bytes.length}`);
  };

  const handleWrite = () => {
    if (inputType === 'string') {
      writeString(inputValue);
    } else if (inputType === 'char') {
      if (inputValue.length > 0) {
        writeByte(inputValue.charCodeAt(0));
      }
    } else {
      const num = parseInt(inputValue, 16) || parseInt(inputValue) || 0;
      writeByte(num);
    }
  };

  const readByte = () => {
    if (state.mode !== 'read') {
      addLog('❌ Cannot read in write mode. Call resetForRead() first');
      return;
    }
    if (state.position >= state.limit) {
      addLog('❌ Buffer underflow: no bytes remaining');
      return;
    }
    const value = state.data[state.position] ?? 0;
    setState(prev => ({
      ...prev,
      position: prev.position + 1,
    }));
    setHasModified(true);
    const charRepr = value >= 32 && value <= 126 ? ` '${String.fromCharCode(value)}'` : '';
    addLog(`readByte() → 0x${value.toString(16).toUpperCase().padStart(2, '0')}${charRepr}, position=${state.position + 1}`);
  };

  const readString = (length: number) => {
    if (state.mode !== 'read') {
      addLog('❌ Cannot read in write mode. Call resetForRead() first');
      return;
    }
    if (state.position + length > state.limit) {
      addLog(`❌ Buffer underflow: need ${length} bytes, have ${state.limit - state.position}`);
      return;
    }
    const bytes = state.data.slice(state.position, state.position + length);
    const str = bytes.map(b => b !== null ? String.fromCharCode(b) : '?').join('');
    setState(prev => ({
      ...prev,
      position: prev.position + length,
    }));
    setHasModified(true);
    addLog(`readString(${length}) → "${str}", position=${state.position + length}`);
  };

  const resetForRead = () => {
    setState(prev => ({
      ...prev,
      limit: prev.position,
      position: 0,
      mode: 'read',
    }));
    setHasModified(true);
    addLog(`resetForRead() → limit=${state.position}, position=0`);
  };

  const resetForWrite = () => {
    setState(prev => ({
      ...prev,
      position: 0,
      limit: prev.capacity,
      mode: 'write',
    }));
    setHasModified(true);
    addLog(`resetForWrite() → position=0, limit=${state.capacity}`);
  };

  const clear = () => {
    setState({
      capacity,
      position: 0,
      limit: capacity,
      data: Array(capacity).fill(null),
      mode: 'write',
    });
    setHasModified(true);
    setLog(['Buffer cleared and reset']);
  };

  const remaining = state.limit - state.position;
  const hasSlice = sliceFrom !== undefined && sliceTo !== undefined;

  return (
    <div className={`${styles.container} ${!isEditing ? styles.viewMode : ''}`}>
      {title && <div className={styles.title}>{title}</div>}
      {subtitle && <div className={styles.subtitle}>{subtitle}</div>}

      <div className={styles.bufferSection}>
        <div className={styles.header}>
          {byteOrder ? (
            <span className={`${styles.byteOrderBadge} ${byteOrder === 'big' ? styles.bigEndian : styles.littleEndian}`}>
              {byteOrder === 'big' ? 'BIG-ENDIAN' : 'LITTLE-ENDIAN'}
            </span>
          ) : !hideMode ? (
            <span className={`${styles.mode} ${state.mode === 'read' ? styles.readMode : styles.writeMode}`}>
              {state.mode.toUpperCase()} MODE
            </span>
          ) : <span />}
          {!hideRemaining && (
            <span className={styles.stats}>
              remaining() = {remaining}
            </span>
          )}
        </div>

        <div
          className={`${styles.buffer} ${!isEditing ? styles.clickable : ''}`}
          onClick={!isEditing ? enableEditing : undefined}
          title={!isEditing ? 'Click to interact with this buffer' : undefined}
        >
          {state.data.map((byte, i) => {
            const isPosition = i === state.position;
            const isBeforeLimit = i < state.limit;
            const hasData = byte !== null;
            const isReadable = state.mode === 'read' && i >= state.position && i < state.limit;
            const isWritable = state.mode === 'write' && i >= state.position && i < state.limit;
            const isHighlighted = highlight.includes(i);
            const isInSlice = hasSlice && i >= sliceFrom && i < sliceTo;

            return (
              <div
                key={i}
                className={`
                  ${styles.cell}
                  ${!isBeforeLimit ? styles.beyondLimit : ''}
                  ${isPosition ? styles.atPosition : ''}
                  ${isReadable ? styles.readable : ''}
                  ${isWritable ? styles.writable : ''}
                  ${isHighlighted ? styles.highlighted : ''}
                  ${isInSlice ? styles.inSlice : ''}
                `}
              >
                <div className={styles.index}>[{i}]</div>
                <div className={styles.value}>
                  {hasData
                    ? (showChars ? byteToChar(byte) : byte.toString(16).padStart(2, '0').toUpperCase())
                    : '—'
                  }
                </div>
                {showChars && hasData && (
                  <div className={styles.hexSmall}>
                    {byte.toString(16).padStart(2, '0')}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {hasSlice && (
          <div className={styles.sliceIndicator}>
            <div
              className={styles.sliceBracket}
              style={{
                left: `calc(${sliceFrom} * (100% / ${capacity}) + 2px)`,
                width: `calc(${sliceTo - sliceFrom} * (100% / ${capacity}) - 4px)`,
              }}
            >
              <span className={styles.sliceLabel}>slice()</span>
            </div>
          </div>
        )}

        {highlightLabel && highlight.length > 0 && (
          <div className={styles.highlightLabel}>{highlightLabel}</div>
        )}

        {!hideMarkers && (
          <div className={styles.markers}>
            <div className={styles.markerRow}>
              <span>position={state.position}</span>
              <span>limit={state.limit}</span>
              <span>capacity={state.capacity}</span>
            </div>
          </div>
        )}

        {!isEditing && (
          <button className={styles.tryItBtn} onClick={enableEditing}>
            ▶ Try it yourself
          </button>
        )}

        {isEditing && (
          <div className={styles.legend}>
            <span className={styles.legendItem}><span className={styles.positionDot}></span> Position</span>
            <span className={styles.legendItem}><span className={styles.readableDot}></span> Readable</span>
            <span className={styles.legendItem}><span className={styles.writableDot}></span> Writable</span>
            <span className={styles.legendItem}><span className={styles.beyondDot}></span> Beyond limit</span>
          </div>
        )}
      </div>

      {isEditing && (
        <>
          <div className={styles.controls}>
            <div className={styles.controlGroup}>
              <label>Type:</label>
              <select
                value={inputType}
                onChange={(e) => setInputType(e.target.value as 'char' | 'byte' | 'string')}
                className={styles.select}
              >
                <option value="char">Char</option>
                <option value="byte">Byte (hex)</option>
                <option value="string">String</option>
              </select>
            </div>

            <div className={styles.controlGroup}>
              <label>Value:</label>
              <input
                type="text"
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                className={styles.input}
                placeholder={inputType === 'byte' ? '0x42' : inputType === 'char' ? 'A' : 'Hello'}
              />
            </div>

            <div className={styles.controlGroup}>
              <label>Write:</label>
              <button onClick={handleWrite} disabled={state.mode !== 'write'}>
                {inputType === 'string' ? 'writeString()' : 'writeByte()'}
              </button>
            </div>

            <div className={styles.controlGroup}>
              <label>Read:</label>
              <button onClick={readByte} disabled={state.mode !== 'read'}>readByte()</button>
              <button onClick={() => readString(remaining)} disabled={state.mode !== 'read' || remaining === 0}>
                readString({remaining})
              </button>
            </div>

            <div className={styles.controlGroup}>
              <label>Mode:</label>
              <button onClick={resetForRead} className={styles.modeBtn}>resetForRead()</button>
              <button onClick={resetForWrite} className={styles.modeBtn}>resetForWrite()</button>
            </div>

            <div className={styles.controlGroup}>
              <button onClick={clear} className={styles.clearBtn}>Clear</button>
              {hasModified && (
                <button onClick={resetToDefault} className={styles.resetBtn}>↺ Reset</button>
              )}
            </div>
          </div>

          <div className={styles.log}>
            {log.map((entry, i) => (
              <div key={i} className={styles.logEntry}>{entry}</div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
