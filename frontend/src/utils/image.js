export const getImageUrl = (image, defaultText = 'No Image') => {
  // 入参归一：null/undefined → ''，其余统一 String()。
  // 避免非字符串（数字/对象）调用 .trim() 抛 TypeError。
  const img = image == null ? '' : String(image)

  if (!img || img.trim() === '') {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="178" height="178" viewBox="0 0 178 178"><rect width="178" height="178" fill="#f5f7fa" rx="8"/><text x="89" y="95" text-anchor="middle" fill="#909399" font-size="12" font-family="sans-serif">${defaultText}</text></svg>`
    // btoa 只接受 Latin1 字符，遇到中文等会抛 InvalidCharacterError；
    // 先 encodeURIComponent（得到纯 ASCII）再 unescape 还原字节，可安全编码任意 Unicode。
    return `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`
  }
  
  if (img.startsWith('http://') || img.startsWith('https://')) {
    return img
  }
  
  if (img.startsWith('/uploads/')) {
    return '/api' + img
  }
  
  if (img.startsWith('/api/uploads/')) {
    return img
  }
  
  if (img.match(/^[0-9a-f-]+\.(png|jpg|jpeg|gif)$/i) || 
      img.match(/^\d{4}\/\d{2}\/\d{2}\//)) {
    return '/api/uploads/' + img
  }
  
  return '/api/uploads/' + img
}

export const getProductImage = (product) => {
  return getImageUrl(product?.image, 'No Product Image')
}

export const getThumbImage = (image) => {
  return getImageUrl(image, 'Thumb')
}
