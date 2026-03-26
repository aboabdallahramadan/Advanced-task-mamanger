import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import { ResizableImage } from './ResizableImage';
import { useStore } from '../store';
import { Note } from '../types';
import { ArrowLeft, Trash2, Bold, Italic, Strikethrough, Code, Heading1, Heading2, Heading3, ImagePlus, Minimize2, Maximize2 } from 'lucide-react';
import { clsx } from 'clsx';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';

export function NoteEditorView() {
    const {
        selectedNoteId,
        selectedNoteGroupId,
        noteGroups,
        projects,
        updateNote,
        deleteNote,
        setCurrentView,
        selectProject,
    } = useStore();

    const [title, setTitle] = useState('');
    const [noteLoaded, setNoteLoaded] = useState(false);
    const [loadedNote, setLoadedNote] = useState<Note | null>(null);
    const saveTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const titleSaveTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const isMountedRef = useRef(true);

    const group = noteGroups.find((g) => g.id === selectedNoteGroupId);
    const linkedProject = loadedNote?.projectId && !loadedNote.groupId
        ? projects.find((p) => p.id === loadedNote.projectId)
        : null;

    const editor = useEditor({
        extensions: [
            StarterKit.configure({
                heading: { levels: [1, 2, 3] },
            }),
            Placeholder.configure({
                placeholder: 'Start writing...',
            }),
            ResizableImage.configure({
                inline: false,
                allowBase64: true,
            }),
        ],
        content: '',
        editorProps: {
            attributes: {
                class: 'focus:outline-none px-6 py-4',
            },
        },
        onUpdate: ({ editor }) => {
            if (!selectedNoteId || !noteLoaded) return;

            if (saveTimeoutRef.current) clearTimeout(saveTimeoutRef.current);
            saveTimeoutRef.current = setTimeout(() => {
                if (isMountedRef.current && selectedNoteId) {
                    updateNote(selectedNoteId, { content: editor.getHTML() });
                }
            }, 500);
        },
    });

    // Load note content
    useEffect(() => {
        if (!selectedNoteId || !editor) return;

        setNoteLoaded(false);
        window.api.notes.getById(selectedNoteId).then((note) => {
            if (!note || !isMountedRef.current) return;
            setTitle(note.title || '');
            setLoadedNote(note);
            editor.commands.setContent(note.content || '');
            setNoteLoaded(true);
        });
    }, [selectedNoteId, editor]);

    // Cleanup: save on unmount
    useEffect(() => {
        isMountedRef.current = true;
        return () => {
            isMountedRef.current = false;
            if (saveTimeoutRef.current) clearTimeout(saveTimeoutRef.current);
            if (titleSaveTimeoutRef.current) clearTimeout(titleSaveTimeoutRef.current);
        };
    }, []);

    // Save content immediately before navigating away
    const saveImmediately = useCallback(() => {
        if (saveTimeoutRef.current) {
            clearTimeout(saveTimeoutRef.current);
            saveTimeoutRef.current = null;
        }
        if (titleSaveTimeoutRef.current) {
            clearTimeout(titleSaveTimeoutRef.current);
            titleSaveTimeoutRef.current = null;
        }
        if (selectedNoteId && editor) {
            updateNote(selectedNoteId, {
                title: title || 'Untitled',
                content: editor.getHTML(),
            });
        }
    }, [selectedNoteId, editor, title, updateNote]);

    const handleBack = () => {
        saveImmediately();
        if (loadedNote?.projectId && !loadedNote.groupId) {
            useStore.getState().setProjectActiveTab('notes');
            selectProject(loadedNote.projectId);
        } else if (selectedNoteGroupId) {
            useStore.getState().selectNoteGroup(selectedNoteGroupId);
        } else {
            setCurrentView('board');
        }
    };

    const handleDelete = async () => {
        if (!selectedNoteId) return;
        if (confirm('Delete this note?')) {
            await deleteNote(selectedNoteId);
        }
    };

    const handleTitleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const newTitle = e.target.value;
        setTitle(newTitle);

        if (titleSaveTimeoutRef.current) clearTimeout(titleSaveTimeoutRef.current);
        titleSaveTimeoutRef.current = setTimeout(() => {
            if (isMountedRef.current && selectedNoteId) {
                updateNote(selectedNoteId, { title: newTitle || 'Untitled' });
            }
        }, 500);
    };

    const handleInsertImage = () => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.onchange = (e) => {
            const file = (e.target as HTMLInputElement).files?.[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = () => {
                const base64 = reader.result as string;
                editor?.chain().focus().setImage({ src: base64 }).run();
            };
            reader.readAsDataURL(file);
        };
        input.click();
    };

    if (!selectedNoteId) {
        return (
            <div className="flex-1 flex items-center justify-center text-surface-500">
                <p>No note selected</p>
            </div>
        );
    }

    return (
        <div className="flex-1 flex flex-col h-full bg-surface-950">
            {/* Header */}
            <div className="px-6 pt-10 pb-3 border-b border-surface-800/40 flex items-center gap-3">
                <button
                    onClick={handleBack}
                    className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                >
                    <ArrowLeft className="w-4 h-4" />
                </button>
                {linkedProject ? (
                    <span className="text-xs text-surface-500">
                        {linkedProject.emoji} {linkedProject.name}
                    </span>
                ) : group ? (
                    <span className="text-xs text-surface-500">
                        {group.emoji} {group.name}
                    </span>
                ) : null}
                <div className="flex-1" />
                <button
                    onClick={handleDelete}
                    className="p-2 rounded-lg text-surface-400 hover:text-danger-400 hover:bg-danger-900/20 transition-all"
                    title="Delete note"
                >
                    <Trash2 className="w-4 h-4" />
                </button>
            </div>

            {/* Title */}
            <div className="px-6 pt-6 pb-2">
                <input
                    type="text"
                    dir={getTextDirection(title)}
                    style={getDirectionStyle(title)}
                    value={title}
                    onChange={handleTitleChange}
                    placeholder="Untitled"
                    className="w-full text-2xl font-bold text-surface-50 bg-transparent border-none outline-none placeholder-surface-600"
                />
            </div>

            {/* Formatting toolbar */}
            {editor && (
                <div className="px-6 py-2 border-b border-surface-800/20 flex items-center gap-0.5">
                    <ToolbarButton
                        onClick={() => editor.chain().focus().toggleBold().run()}
                        active={editor.isActive('bold')}
                        title="Bold"
                    >
                        <Bold className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    <ToolbarButton
                        onClick={() => editor.chain().focus().toggleItalic().run()}
                        active={editor.isActive('italic')}
                        title="Italic"
                    >
                        <Italic className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    <ToolbarButton
                        onClick={() => editor.chain().focus().toggleStrike().run()}
                        active={editor.isActive('strike')}
                        title="Strikethrough"
                    >
                        <Strikethrough className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    <ToolbarButton
                        onClick={() => editor.chain().focus().toggleCode().run()}
                        active={editor.isActive('code')}
                        title="Code"
                    >
                        <Code className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    <div className="w-px h-4 bg-surface-700 mx-1" />
                    <ToolbarButton
                        onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
                        active={editor.isActive('heading', { level: 1 })}
                        title="Heading 1"
                    >
                        <Heading1 className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    <ToolbarButton
                        onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
                        active={editor.isActive('heading', { level: 2 })}
                        title="Heading 2"
                    >
                        <Heading2 className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    <ToolbarButton
                        onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
                        active={editor.isActive('heading', { level: 3 })}
                        title="Heading 3"
                    >
                        <Heading3 className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    <div className="w-px h-4 bg-surface-700 mx-1" />
                    <ToolbarButton
                        onClick={handleInsertImage}
                        active={false}
                        title="Insert image"
                    >
                        <ImagePlus className="w-3.5 h-3.5" />
                    </ToolbarButton>
                    {editor.isActive('image') && (
                        <>
                            <div className="w-px h-4 bg-surface-700 mx-1" />
                            {['25%', '50%', '75%', '100%'].map((size) => (
                                <button
                                    key={size}
                                    onClick={() => editor.chain().focus().updateAttributes('image', { width: size }).run()}
                                    className="px-1.5 py-0.5 text-2xs rounded text-surface-400 hover:text-surface-100 hover:bg-surface-700 transition-colors"
                                    title={`Resize to ${size}`}
                                >
                                    {size}
                                </button>
                            ))}
                        </>
                    )}
                </div>
            )}

            {/* Editor */}
            <div className="flex-1 overflow-y-auto custom-scrollbar">
                <EditorContent editor={editor} />
            </div>
        </div>
    );
}

function ToolbarButton({
    onClick,
    active,
    title,
    children,
}: {
    onClick: () => void;
    active: boolean;
    title: string;
    children: React.ReactNode;
}) {
    return (
        <button
            onClick={onClick}
            title={title}
            className={clsx(
                'p-1.5 rounded transition-colors',
                active
                    ? 'bg-accent-600/30 text-accent-300'
                    : 'text-surface-300 hover:text-surface-100 hover:bg-surface-700',
            )}
        >
            {children}
        </button>
    );
}
