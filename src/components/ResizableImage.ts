import Image from '@tiptap/extension-image';

export const ResizableImage = Image.extend({
    addAttributes() {
        return {
            ...this.parent?.(),
            width: {
                default: null,
                parseHTML: (element) => element.style.width || element.getAttribute('width') || null,
                renderHTML: (attributes) => {
                    if (!attributes.width) return {};
                    return { style: `width: ${attributes.width}` };
                },
            },
        };
    },
});
