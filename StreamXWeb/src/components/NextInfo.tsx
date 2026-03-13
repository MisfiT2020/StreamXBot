import { useCallback, useMemo, useState, type ReactNode } from 'react'
import shuffleIconUrl from '../assets/shuffle.svg'
import repeatIconUrl from '../assets/repeat.svg'
import { UpcomingTracks } from './UpcomingTracks.js'
import type { Song } from '../types/index.js'
import {
  DndContext,
  PointerSensor,
  TouchSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import { restrictToFirstScrollableAncestor, restrictToVerticalAxis } from '@dnd-kit/modifiers'
import { SortableContext, arrayMove, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { jamQueueReorder } from '../services/jamApi.js'

type RepeatMode = 'off' | 'all' | 'one'

const fallbackCover =
  'https://marketplace.canva.com/EAGnmDwaNpA/1/0/1600w/canva-beige-black-and-white-simple-minimalist-summer-mix-music-album-cover-Hnl8kV6GDeY.jpg'

const SortableNextInfoItem = ({ song, disabled }: { song: Song; disabled: boolean }) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: song._id,
    disabled,
    transition: {
      duration: 350,
      easing: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
    },
  })

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: isDragging ? 'none' : transition,
    touchAction: isDragging ? 'none' : 'pan-y',
  }

  return (
    <div ref={setNodeRef} className="expanded-player-queue-item" role="listitem" data-dragging={isDragging ? 'true' : 'false'} style={style}>
      <img className="expanded-player-queue-cover" src={song.cover_url || fallbackCover} alt="" aria-hidden="true" />
      <div className="expanded-player-queue-text">
        <div className="expanded-player-queue-name" title={song.title}>
          {song.title}
        </div>
        <div className="expanded-player-queue-artist" title={song.artist}>
          {song.artist}
        </div>
      </div>
      <div className="expanded-player-queue-grip" {...attributes} {...listeners} data-disabled={disabled ? 'true' : 'false'} aria-hidden="true" />
    </div>
  )
}

type NextInfoProps = {
  coverUrl: string
  title: ReactNode
  artist: ReactNode
  isShuffleOn: boolean
  setIsShuffleOn: (next: boolean) => void
  repeatMode: RepeatMode
  setRepeatMode: (next: RepeatMode) => void
  upcomingTracks?: Song[] | null
  disabled?: boolean
}

export const NextInfo = ({
  coverUrl,
  title,
  artist,
  isShuffleOn,
  setIsShuffleOn,
  repeatMode,
  setRepeatMode,
  upcomingTracks = null,
  disabled = false,
}: NextInfoProps) => {
  const { externalJamId, externalCanEditQueue, externalUpcoming, setExternalUpcoming } = usePlayerPlayback()
  const [activeId, setActiveId] = useState<string | null>(null)

  const itemIds = useMemo(() => externalUpcoming.map((s) => s._id), [externalUpcoming])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 120, tolerance: 6 } }),
  )

  const onDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id))
  }, [])

  const onDragCancel = useCallback(() => {
    setActiveId(null)
  }, [])

  const onDragEnd = useCallback(
    async (event: DragEndEvent) => {
      const { active, over } = event
      setActiveId(null)
      if (!over || active.id === over.id) return
      if (!externalJamId) return
      if (!externalCanEditQueue) return

      const oldIndex = externalUpcoming.findIndex((t) => t._id === active.id)
      const newIndex = externalUpcoming.findIndex((t) => t._id === over.id)
      if (oldIndex < 0 || newIndex < 0) return

      const previous = externalUpcoming
      const next = arrayMove(externalUpcoming, oldIndex, newIndex)
      setExternalUpcoming(next)

      try {
        await jamQueueReorder(externalJamId, next.map((t) => t._id))
      } catch (err) {
        setExternalUpcoming(previous)
        console.error('Failed to reorder jam queue:', err)
      }
    },
    [externalCanEditQueue, externalJamId, externalUpcoming, setExternalUpcoming],
  )

  return (
    <>
      <div className="expanded-player-queue-now" aria-label="Now playing">
        <img className="expanded-player-queue-now-cover" src={coverUrl} alt="" aria-hidden="true" />
        <div className="expanded-player-queue-now-text">
          <div className="expanded-player-queue-now-title" title={typeof title === 'string' ? title : undefined}>
            {title}
          </div>
          <div className="expanded-player-queue-now-artist" title={typeof artist === 'string' ? artist : undefined}>
            {artist}
          </div>
        </div>
      </div>

      <div className="expanded-player-queue-controls" aria-label="Playback options">
        <button
          className={`expanded-player-queue-control${isShuffleOn ? ' is-active' : ''}`}
          type="button"
          aria-label={isShuffleOn ? 'Shuffle on' : 'Shuffle off'}
          disabled={disabled}
          onClick={() => setIsShuffleOn(!isShuffleOn)}
        >
          <img className="expanded-player-queue-control-icon" src={shuffleIconUrl} alt="" aria-hidden="true" />
        </button>
        <button
          className={`expanded-player-queue-control${repeatMode === 'off' ? '' : ' is-active'}`}
          type="button"
          aria-label={repeatMode === 'one' ? 'Repeat one' : repeatMode === 'all' ? 'Repeat all' : 'Repeat off'}
          disabled={disabled}
          onClick={() => setRepeatMode(repeatMode === 'off' ? 'all' : repeatMode === 'all' ? 'one' : 'off')}
        >
          <img className="expanded-player-queue-control-icon" src={repeatIconUrl} alt="" aria-hidden="true" />
          {repeatMode === 'one' ? <span className="expanded-player-queue-repeat-one">1</span> : null}
        </button>
      </div>

      {upcomingTracks ? (
        <section className="expanded-player-queue" aria-label="Up next" data-empty={upcomingTracks.length === 0 ? 'true' : 'false'}>
          <div className="expanded-player-queue-title">Continue Playing</div>

          {upcomingTracks.length === 0 ? (
            <div className="expanded-player-queue-empty">Nothing to show here</div>
          ) : (
            <div className="expanded-player-queue-scroll">
              <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                modifiers={[restrictToVerticalAxis, restrictToFirstScrollableAncestor]}
                onDragStart={onDragStart}
                onDragCancel={onDragCancel}
                onDragEnd={onDragEnd}
              >
                <SortableContext items={itemIds} strategy={verticalListSortingStrategy}>
                  <div className="expanded-player-queue-list" role="list" data-dragging={activeId ? 'true' : 'false'}>
                    {externalUpcoming.map((song) => (
                      <SortableNextInfoItem key={song._id} song={song} disabled={!externalCanEditQueue} />
                    ))}
                  </div>
                </SortableContext>
              </DndContext>
            </div>
          )}
        </section>
      ) : (
        <UpcomingTracks dropAnimationMs={120} dropAnimationEasing="cubic-bezier(0.22, 1, 0.36, 1)" />
      )}
    </>
  )
}
