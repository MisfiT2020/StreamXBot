import {
  DndContext,
  DragOverlay,
  PointerSensor,
  TouchSensor,
  closestCenter,
  defaultDropAnimationSideEffects,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
  type DropAnimation,
} from "@dnd-kit/core";
import {
  restrictToFirstScrollableAncestor,
  restrictToVerticalAxis,
} from "@dnd-kit/modifiers";
import {
  SortableContext,
  arrayMove,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MutableRefObject,
} from "react";
import { createPortal } from "react-dom";

import { usePlayerPlayback } from "../context/PlayerContext.js";
import type { Song } from "../types/index.js";

const fallbackCover =
  "https://marketplace.canva.com/EAGnmDwaNpA/1/0/1600w/canva-beige-black-and-white-simple-minimalist-summer-mix-music-album-cover-Hnl8kV6GDeY.jpg";

const QueueItem = ({
  song,
  isOverlay,
  isDragging,
  onActivate,
  onMenuClick,
  suppressClickUntilRef,
}: {
  song: Song;
  isOverlay?: boolean;
  isDragging?: boolean;
  onActivate?: (song: Song) => void;
  onMenuClick?: (song: Song, event: React.MouseEvent) => void;
  suppressClickUntilRef?: MutableRefObject<number>;
}) => {
  return (
    <button
      className="expanded-player-queue-item"
      type="button"
      role="listitem"
      data-dragging={isDragging ? "true" : "false"}
      data-overlay={isOverlay ? "true" : "false"}
      disabled={isOverlay ? true : undefined}
      onClick={() => {
        if (!onActivate || !suppressClickUntilRef) return;
        if (performance.now() < suppressClickUntilRef.current) return;
        onActivate(song);
      }}
    >
      <img
        className="expanded-player-queue-cover"
        src={song.cover_url || fallbackCover}
        alt=""
        aria-hidden="true"
      />
      <div className="expanded-player-queue-text">
        <div className="expanded-player-queue-name" title={song.title}>
          {song.title}
        </div>
        <div className="expanded-player-queue-artist" title={song.artist}>
          {song.artist}
        </div>
      </div>
      {!isOverlay && onMenuClick && (
        <button
          className="expanded-player-queue-menu-btn"
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onMenuClick(song, e);
          }}
          aria-label="More options"
        >
          <svg viewBox="0 0 16 16" fill="currentColor">
            <circle cx="8" cy="3" r="1.5" />
            <circle cx="8" cy="8" r="1.5" />
            <circle cx="8" cy="13" r="1.5" />
          </svg>
        </button>
      )}
    </button>
  );
};

const SortableQueueItem = ({
  song,
  onActivate,
  onMenuClick,
  suppressClickUntilRef,
}: {
  song: Song;
  onActivate: (song: Song) => void;
  onMenuClick: (song: Song, event: React.MouseEvent) => void;
  suppressClickUntilRef: MutableRefObject<number>;
}) => {
  const { attributes, listeners, setNodeRef, transform, isDragging } =
    useSortable({
      id: song._id,
      transition: {
        duration: 350,
        easing: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
      },
    });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: isDragging ? "none" : undefined,
    touchAction: isDragging ? "none" : "pan-y",
  };

  return (
    <button
      ref={setNodeRef}
      className="expanded-player-queue-item"
      type="button"
      role="listitem"
      data-dragging={isDragging ? "true" : "false"}
      style={style}
      onClick={() => {
        if (performance.now() < suppressClickUntilRef.current) return;
        onActivate(song);
      }}
    >
      <img
        className="expanded-player-queue-cover"
        src={song.cover_url || fallbackCover}
        alt=""
        aria-hidden="true"
      />
      <div className="expanded-player-queue-text">
        <div className="expanded-player-queue-name" title={song.title}>
          {song.title}
        </div>
        <div className="expanded-player-queue-artist" title={song.artist}>
          {song.artist}
        </div>
      </div>

      <button
        className="expanded-player-queue-menu-btn"
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onMenuClick(song, e);
        }}
        aria-label="More options"
      >
        <svg viewBox="0 0 16 16" fill="currentColor">
          <circle cx="8" cy="3" r="1.5" />
          <circle cx="8" cy="8" r="1.5" />
          <circle cx="8" cy="13" r="1.5" />
        </svg>
      </button>

      {/* drag handle */}
      <div className="expanded-player-queue-grip" {...attributes} {...listeners} />
    </button>
  );
};

export const UpcomingTracks = ({
  limit = Number.POSITIVE_INFINITY,
  dropAnimationEasing = "cubic-bezier(0.16, 1, 0.3, 1)",
}: {
  limit?: number;
  dropAnimationMs?: number;
  dropAnimationEasing?: string;
}) => {
  const { upcoming, setUpcoming, playFromQueue, playNextTrack } = usePlayerPlayback();

  const suppressClickUntilRef = useRef(0);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [isDraggingList, setIsDraggingList] = useState(false);
  const [menuSong, setMenuSong] = useState<Song | null>(null);
  const [menuPosition, setMenuPosition] = useState<{ x: number; y: number } | null>(null);
  const menuOpenedAtRef = useRef<number>(0);
  const clearActiveTimeoutRef = useRef<number | null>(null);

  // Disable drop animation to prevent blinking
  const dropAnimation = useMemo<DropAnimation>(
    () => ({
      duration: 0,
      easing: dropAnimationEasing,
      sideEffects: defaultDropAnimationSideEffects({
        styles: {
          active: { opacity: "1" },
        },
      }),
    }),
    [dropAnimationEasing]
  );

  const items = useMemo(() => {
    return upcoming.slice(0, limit);
  }, [limit, upcoming]);

  const itemIds = useMemo(() => items.map((s) => s._id), [items]);

  const sensors = useSensors(
    useSensor(PointerSensor, {
      // Lower distance = faster pickup; tweak to taste.
      activationConstraint: { distance: 8 },
    }),
    useSensor(TouchSensor, { activationConstraint: { delay: 120, tolerance: 6 } })
  );

  const onDragStart = useCallback((event: DragStartEvent) => {
    suppressClickUntilRef.current = performance.now() + 650;

    if (clearActiveTimeoutRef.current !== null) {
      window.clearTimeout(clearActiveTimeoutRef.current);
      clearActiveTimeoutRef.current = null;
    }
    setIsDraggingList(true);
    setActiveId(String(event.active.id));
  }, []);

  const onDragCancel = useCallback(() => {
    suppressClickUntilRef.current = performance.now() + 650;
    setIsDraggingList(false);

    if (clearActiveTimeoutRef.current !== null) window.clearTimeout(clearActiveTimeoutRef.current);
    clearActiveTimeoutRef.current = window.setTimeout(() => {
      setActiveId(null);
      clearActiveTimeoutRef.current = null;
    }, 150);
  }, []);

  const onDragEnd = useCallback(
    (event: DragEndEvent) => {
      suppressClickUntilRef.current = performance.now() + 650;
      
      const { active, over } = event;
      
      setIsDraggingList(false);
      setActiveId(null);
      
      if (over && active.id !== over.id) {
        setUpcoming((prev: Song[]) => {
          const oldIndex = prev.findIndex((s) => s._id === active.id);
          const newIndex = prev.findIndex((s) => s._id === over.id);
          if (oldIndex < 0 || newIndex < 0) return prev;
          return arrayMove(prev, oldIndex, newIndex);
        });
      }

      if (clearActiveTimeoutRef.current !== null) {
        window.clearTimeout(clearActiveTimeoutRef.current);
        clearActiveTimeoutRef.current = null;
      }
    },
    [setUpcoming]
  );

  const activeSong = useMemo(
    () => (activeId ? items.find((s) => s._id === activeId) ?? null : null),
    [activeId, items]
  );

  const isEmpty = items.length === 0;

  const handleMenuClick = useCallback((song: Song, event: React.MouseEvent) => {
    const rect = event.currentTarget.getBoundingClientRect();
    menuOpenedAtRef.current = performance.now();
    setMenuSong(song);
    setMenuPosition({ x: rect.right, y: rect.top });
  }, []);

  const closeMenu = useCallback(() => {
    setMenuSong(null);
    setMenuPosition(null);
  }, []);

  useEffect(() => {
    if (!menuSong) return
    let didClose = false
    const onScrollLike = () => {
      if (didClose) return
      if (performance.now() - menuOpenedAtRef.current < 140) return
      didClose = true
      closeMenu()
    }
    window.addEventListener('scroll', onScrollLike, { passive: true, capture: true })
    window.addEventListener('wheel', onScrollLike, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScrollLike, { capture: true } as AddEventListenerOptions)
      window.removeEventListener('wheel', onScrollLike)
    }
  }, [closeMenu, menuSong])

  const handleAddToFavorite = useCallback(() => {
    if (!menuSong) return;
    console.log('Add to favorite:', menuSong);
    closeMenu();
  }, [menuSong, closeMenu]);

  const handlePlayNext = useCallback(() => {
    if (!menuSong) return;
    playNextTrack(menuSong);
    closeMenu();
  }, [menuSong, playNextTrack, closeMenu]);

  const handleAddToPlaylist = useCallback(() => {
    if (!menuSong) return;
    console.log('Add to playlist:', menuSong);
    closeMenu();
  }, [menuSong, closeMenu]);

  return (
    <section className="expanded-player-queue" aria-label="Up next" data-empty={isEmpty ? 'true' : 'false'}>
      <div className="expanded-player-queue-title">Continue Playing</div>

      {isEmpty ? (
        <div className="expanded-player-queue-empty">Nothing to show here</div>
      ) : (
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          modifiers={[restrictToVerticalAxis, restrictToFirstScrollableAncestor]}
          onDragStart={onDragStart}
          onDragCancel={onDragCancel}
          onDragEnd={onDragEnd}
        >
          <div className="expanded-player-queue-scroll">
            <SortableContext
              items={itemIds}
              strategy={verticalListSortingStrategy}
            >
              <div className="expanded-player-queue-list" role="list" data-dragging={isDraggingList ? "true" : "false"}>
                {items.map((song) => (
                  <SortableQueueItem
                    key={song._id}
                    song={song}
                    onActivate={playFromQueue}
                    onMenuClick={handleMenuClick}
                    suppressClickUntilRef={suppressClickUntilRef}
                  />
                ))}
              </div>
            </SortableContext>
          </div>

          {createPortal(
            <DragOverlay adjustScale={false} dropAnimation={dropAnimation}>
              {activeSong ? (
                <QueueItem song={activeSong} isOverlay={true} isDragging={true} />
              ) : null}
            </DragOverlay>,
            document.body
          )}
        </DndContext>
      )}

      {menuSong && menuPosition && createPortal(
        <>
          <div className="queue-menu-backdrop" onClick={closeMenu} />
          <div 
            className="queue-menu" 
            style={{ 
              position: 'fixed',
              top: `${menuPosition.y}px`,
              right: `${window.innerWidth - menuPosition.x + 8}px`,
            }}
          >
            <div className="queue-menu-section">
              <button className="queue-menu-item" onClick={handleAddToFavorite}>
                <svg className="queue-menu-icon" viewBox="0 0 60 60" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"/>
                </svg>
                <span>Add to Favorite</span>
              </button>
            </div>
            
            <div className="queue-menu-divider" />
            
            <div className="queue-menu-section">
              <button className="queue-menu-item" onClick={handlePlayNext}>
                <svg className="queue-menu-icon" viewBox="0 0 32 28" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M18.14 20.68c.365 0 .672-.107 1.038-.323l8.508-4.997c.623-.365.938-.814.938-1.37 0-.564-.307-.988-.938-1.361l-8.508-4.997c-.366-.216-.68-.324-1.046-.324-.73 0-1.337.556-1.337 1.569v4.773c-.108-.399-.406-.73-.904-1.021L7.382 7.632c-.357-.216-.672-.324-1.037-.324-.73 0-1.345.556-1.345 1.569v10.235c0 1.013.614 1.569 1.345 1.569.365 0 .68-.108 1.037-.324l8.509-4.997c.49-.29.796-.631.904-1.038v4.79c0 1.013.615 1.569 1.345 1.569z"/>
                </svg>
                <span>Play Next</span>
              </button>
              
              <button className="queue-menu-item" onClick={handleAddToPlaylist}>
                <svg className="queue-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h13v2H3v-2zm16 0v3h3v2h-3v3h-2v-3h-3v-2h3v-3h2z"/>
                </svg>
                <span>Add to Playlist</span>
              </button>
            </div>
          </div>
        </>,
        document.body
      )}
    </section>
  );
};
