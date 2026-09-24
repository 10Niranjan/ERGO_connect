/** Saves an object URL from a download endpoint under the file's name, then frees it. */
export function saveObjectUrl(url: string, fileName: string) {
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
