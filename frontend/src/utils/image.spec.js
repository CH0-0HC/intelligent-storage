import { describe, it, expect } from 'vitest'
import { getImageUrl, getProductImage, getThumbImage } from '@/utils/image'

const SVG_PREFIX = 'data:image/svg+xml;base64,'

// 把占位图 data URL 还原成 svg 源码，用于断言中文文案没有在 base64 环节被破坏。
const decodeSvg = (url) => Buffer.from(url.slice(SVG_PREFIX.length), 'base64').toString('utf8')

describe('getImageUrl', () => {
  it('传中文 defaultText 不再抛异常（P1-9 修复防回归）', () => {
    expect(() => getImageUrl('', '暂无图片')).not.toThrow()
    const url = getImageUrl(null, '商品图')
    expect(url.startsWith(SVG_PREFIX)).toBe(true)
    // 关键：中文必须完整还原（历史 bug 是 btoa 直接编码中文抛 InvalidCharacterError）
    expect(decodeSvg(url)).toContain('商品图')
  })

  it('null / undefined / 数字 123 入参不抛', () => {
    expect(() => getImageUrl(null)).not.toThrow()
    expect(() => getImageUrl(undefined)).not.toThrow()
    expect(() => getImageUrl(123)).not.toThrow()
    expect(getImageUrl(123)).toBe('/api/uploads/123')
  })

  it('/uploads/x.png → /api/uploads/x.png', () => {
    expect(getImageUrl('/uploads/x.png')).toBe('/api/uploads/x.png')
  })

  it('http(s):// 直通不改写', () => {
    expect(getImageUrl('http://cdn.example.com/a.png')).toBe('http://cdn.example.com/a.png')
    expect(getImageUrl('https://cdn.example.com/a.png')).toBe('https://cdn.example.com/a.png')
  })

  it('/api/uploads/ 已带前缀则不重复拼接', () => {
    expect(getImageUrl('/api/uploads/x.png')).toBe('/api/uploads/x.png')
  })

  it('空串走默认占位图，返回值以 data:image/svg+xml;base64, 开头', () => {
    const url = getImageUrl('')
    expect(url.startsWith(SVG_PREFIX)).toBe(true)
    expect(decodeSvg(url)).toContain('No Image')
  })

  it('纯空白串也走占位图', () => {
    expect(getImageUrl('   ').startsWith(SVG_PREFIX)).toBe(true)
  })

  it('相对路径补 /api/uploads/ 前缀', () => {
    expect(getImageUrl('abc.png')).toBe('/api/uploads/abc.png')
    expect(getImageUrl('2024/05/01/pic.png')).toBe('/api/uploads/2024/05/01/pic.png')
  })

  it('getProductImage / getThumbImage 空图走各自占位文案', () => {
    expect(decodeSvg(getProductImage({ image: '' }))).toContain('No Product Image')
    expect(decodeSvg(getThumbImage(''))).toContain('Thumb')
    expect(getProductImage({ image: '/uploads/p.png' })).toBe('/api/uploads/p.png')
  })
})
