// 【R21】性能日志只在开发环境输出。
// 生产构建里这些 console.log 会在每次度量、每次资源加载时触发 ——
// 既是控制台噪音，也会让「控制台必须干净」的验收（verify-console-and-dropdown）失真。
const DEBUG = import.meta.env.DEV

export const performanceMonitor = {
  init () {
    this.setupPerformanceObserver()
    this.setupNavigationTiming()
    this.setupResourceTiming()
  },

  setupPerformanceObserver () {
    if ('PerformanceObserver' in window) {
      const observer = new PerformanceObserver((entryList) => {
        for (const entry of entryList.getEntries()) {
          if (DEBUG && entry.entryType === 'measure') {
            console.log(`Performance Measure: ${entry.name} - ${entry.duration.toFixed(2)}ms`)
          } else if (DEBUG && entry.entryType === 'paint') {
            console.log(`Paint Time: ${entry.name} - ${entry.startTime.toFixed(2)}ms`)
          }
        }
      })

      observer.observe({ entryTypes: ['measure', 'paint'] })
    }
  },

  setupNavigationTiming () {
    window.addEventListener('load', () => {
      const timing = performance.getEntriesByType('navigation')[0]
      if (timing) {
        const metrics = {
          domContentLoaded: timing.domContentLoadedEventEnd - timing.navigationStart,
          load: timing.loadEventEnd - timing.navigationStart,
          firstByte: timing.responseStart - timing.navigationStart,
          dns: timing.domainLookupEnd - timing.domainLookupStart,
          tcp: timing.connectEnd - timing.connectStart,
          request: timing.responseEnd - timing.requestStart
        }
        if (DEBUG) console.log('Navigation Timing:', metrics)
      }
    })
  },

  setupResourceTiming () {
    window.addEventListener('load', () => {
      const resources = performance.getEntriesByType('resource')
      const slowResources = resources.filter(r => r.duration > 300)
      if (slowResources.length > 0) {
        console.warn('Slow resources detected:', slowResources.map(r => ({
          name: r.name.split('/').pop(),
          duration: r.duration.toFixed(2) + 'ms'
        })))
      }
    })
  },

  mark (name) {
    if ('performance' in window) {
      performance.mark(name)
    }
  },

  measure (name, startMark, endMark) {
    if ('performance' in window) {
      performance.measure(name, startMark, endMark)
    }
  }
}
