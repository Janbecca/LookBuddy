// Native operations deliberately separate position, size and z-order.
// Electron's DIP rectangle round trip can add a pixel on each move at 150% DPI.
function createWindowsWindow(win, screen) {
  if (process.platform !== 'win32') return null;
  const koffi = require('koffi');
  const user32 = koffi.load('user32.dll');
  const SetWindowPos = user32.func('int __stdcall SetWindowPos(void *window, void *after, int x, int y, int width, int height, uint32_t flags)');
  const GetWindow = user32.func('void * __stdcall GetWindow(void *window, uint32_t command)');
  const GetWindowRect = user32.func('int __stdcall GetWindowRect(void *window, _Out_ void *rect)');
  const handleOf = window => {
    const bytes = window.getNativeWindowHandle();
    return bytes.length === 8 ? bytes.readBigUInt64LE() : BigInt(bytes.readUInt32LE());
  };
  const handle = handleOf(win);
  const NOSIZE = 0x0001, NOMOVE = 0x0002, NOZORDER = 0x0004, NOACTIVATE = 0x0010;
  function call(after, x, y, flags) {
    if (!SetWindowPos(handle, after, x, y, 0, 0, flags)) throw new Error('Cannot reposition LookBuddy window');
  }
  return {
    move(x, y) {
      const point = screen.dipToScreenPoint({ x, y });
      call(null, point.x, point.y, NOSIZE | NOZORDER | NOACTIVATE);
    },
    raise() { call(-1n, 0, 0, NOSIZE | NOMOVE | NOACTIVATE); },
    physicalBounds() {
      const rect = Buffer.alloc(16);
      if (!GetWindowRect(handle, rect)) throw new Error('Cannot read LookBuddy window bounds');
      return { x: rect.readInt32LE(0), y: rect.readInt32LE(4), width: rect.readInt32LE(8) - rect.readInt32LE(0), height: rect.readInt32LE(12) - rect.readInt32LE(4) };
    },
    isAbove(other) {
      const target = handleOf(other);
      let current = GetWindow(handle, 2); // GW_HWNDNEXT: next sibling below this window.
      for (let i = 0; current && i < 1000; i++, current = GetWindow(current, 2)) if (current === target) return true;
      return false;
    },
  };
}

module.exports = { createWindowsWindow };
