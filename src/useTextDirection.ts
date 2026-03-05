/**
 * Detects text direction based on the first strong directional character.
 * Returns 'rtl' for Arabic, Hebrew, etc. and 'ltr' otherwise.
 */

const RTL_REGEX = /[\u0591-\u07FF\u200F\u202B\u202E\uFB1D-\uFDFD\uFE70-\uFEFC]/;

export function getTextDirection(text: string): 'rtl' | 'ltr' {
    return RTL_REGEX.test(text) ? 'rtl' : 'ltr';
}

export function getDirectionStyle(text: string): React.CSSProperties {
    const dir = getTextDirection(text);
    return { direction: dir, textAlign: dir === 'rtl' ? 'right' : 'left' };
}
