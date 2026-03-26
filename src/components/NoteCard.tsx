import React from 'react';
import { Note } from '../types';
import { Trash2, GripVertical } from 'lucide-react';
import { clsx } from 'clsx';
import { format, parseISO } from 'date-fns';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

function stripHtml(html: string): string {
    return html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
}

function extractFirstImage(html: string): string | null {
    const match = html.match(/<img[^>]+src="([^"]+)"/);
    return match ? match[1] : null;
}

interface NoteCardProps {
    note: Note;
    onClick: () => void;
    onDelete: () => void;
    sortable?: boolean;
}

export function NoteCard({ note, onClick, onDelete, sortable = false }: NoteCardProps) {
    const snippet = note.content ? stripHtml(note.content).slice(0, 200) : '';
    const thumbnail = note.content ? extractFirstImage(note.content) : null;

    const {
        attributes,
        listeners,
        setNodeRef,
        transform,
        transition,
        isDragging,
    } = useSortable({ id: note.id, disabled: !sortable });

    const style = sortable
        ? {
              transform: CSS.Transform.toString(transform),
              transition,
              opacity: isDragging ? 0.4 : 1,
          }
        : undefined;

    return (
        <div
            ref={sortable ? setNodeRef : undefined}
            style={style}
            onClick={onClick}
            className={clsx(
                'group relative flex flex-col rounded-xl cursor-pointer transition-all',
                'border border-surface-800/40 bg-surface-900/70',
                'shadow-sm shadow-black/20',
                'hover:shadow-md hover:shadow-black/30 hover:border-surface-700/60 hover:scale-[1.01]',
                isDragging && 'shadow-xl shadow-black/40 z-10',
            )}
        >
            {/* Drag handle */}
            {sortable && (
                <button
                    {...attributes}
                    {...listeners}
                    className="absolute top-2 left-2 opacity-0 group-hover:opacity-100 p-1 text-surface-500 hover:text-surface-300 rounded-lg bg-surface-900/80 backdrop-blur-sm transition-all cursor-grab active:cursor-grabbing z-10"
                    onClick={(e) => e.stopPropagation()}
                    aria-label="Drag to reorder"
                >
                    <GripVertical className="w-3.5 h-3.5" />
                </button>
            )}

            {/* Image thumbnail */}
            {thumbnail && (
                <div className="w-full overflow-hidden rounded-t-xl">
                    <img
                        src={thumbnail}
                        alt=""
                        className="w-full h-32 object-cover"
                    />
                </div>
            )}

            {/* Content */}
            <div className="p-4 flex flex-col gap-1.5">
                {note.title && note.title !== 'Untitled' && (
                    <h4
                        dir={getTextDirection(note.title)}
                        style={getDirectionStyle(note.title)}
                        className="text-sm font-semibold text-surface-100 line-clamp-2 leading-snug"
                    >
                        {note.title}
                    </h4>
                )}

                {snippet && (
                    <p className="text-xs text-surface-400 line-clamp-4 leading-relaxed">
                        {snippet}
                    </p>
                )}

                <span className="text-2xs text-surface-600 mt-1">
                    {format(parseISO(note.updatedAt), 'MMM d, yyyy')}
                </span>
            </div>

            {/* Delete button */}
            <button
                onClick={(e) => {
                    e.stopPropagation();
                    onDelete();
                }}
                className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 p-1.5 text-surface-500 hover:text-danger-400 rounded-lg bg-surface-900/80 hover:bg-danger-900/20 transition-all backdrop-blur-sm"
                title="Delete note"
            >
                <Trash2 className="w-3.5 h-3.5" />
            </button>
        </div>
    );
}
