// Expose the Kotlin/Wasm module as the UMD global `compiler` so the playground's
// wasmLoader.js can access the az* exports via globalThis.compiler (matching the
// original bundle contract).
config.output = config.output || {};
config.output.library = { name: 'compiler', type: 'umd' };
config.output.globalObject = 'globalThis';
