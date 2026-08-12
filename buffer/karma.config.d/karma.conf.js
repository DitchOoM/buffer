config.client = config.client || {}
config.client.mocha = config.client.mocha || {}
// Allocator stress tests (SustainedAllocationChurnTest churns 70k buffers per row) hold the browser's
// event loop for well over the 10s these used to allow, which karma reports as an idle disconnect
// ("no message in 10000 ms") rather than as a slow test. Node runs the same suite without complaint;
// only the browser runner needs the headroom.
config.client.mocha.timeout = 120000
config.browserNoActivityTimeout = 120000
config.browserDisconnectTimeout = 30000

// Enable SharedArrayBuffer in ChromeHeadless without CORS headers
config.customLaunchers = config.customLaunchers || {}
config.customLaunchers.ChromeHeadlessWithSharedArrayBuffer = {
    base: 'ChromeHeadless',
    flags: ['--enable-features=SharedArrayBuffer']
}
config.browsers = ['ChromeHeadlessWithSharedArrayBuffer']
