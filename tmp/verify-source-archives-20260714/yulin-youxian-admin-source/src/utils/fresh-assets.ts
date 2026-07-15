export function resolveFreshAssetUrl(url?: string) {
    if (!url)
        return '';
    if (/^https?:\/\//.test(url) || url.startsWith('//'))
        return url;
    if (!url.startsWith('/assets/') && !url.startsWith('/uploads/'))
        return url;
    const apiUrl = String(import.meta.env.VITE_API_URL || '').trim();
    if (!apiUrl || apiUrl === '/')
        return url;
    return `${apiUrl.replace(/\/+$/, '')}${url}`;
}
