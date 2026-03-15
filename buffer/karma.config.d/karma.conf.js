config.client = config.client || {}
config.client.mocha = config.client.mocha || {}
config.client.mocha.timeout = 10000
config.browserNoActivityTimeout = 10000
config.browserDisconnectTimeout = 10000

// Enable SharedArrayBuffer in ChromeHeadless without CORS headers
config.customLaunchers = config.customLaunchers || {}
config.customLaunchers.ChromeHeadlessWithSharedArrayBuffer = {
    base: 'ChromeHeadless',
    flags: ['--enable-features=SharedArrayBuffer']
}
config.browsers = ['ChromeHeadlessWithSharedArrayBuffer']
