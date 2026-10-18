/**
 * The app's whole icon vocabulary, in one place.
 *
 * Every icon is a 16px, 1.5px-stroke, `currentColor` line glyph (defaults set once by
 * <LucideProvider> in main.tsx) — the IntelliJ-style monochrome language. Icons never
 * carry their own colour: they inherit it from the button/chip they sit in, so theming,
 * hover and state colouring all come free. Colour encodes *state* only (see styles.css
 * "icon + colour policy"), never decoration.
 *
 * Importing through this module rather than from 'lucide-react' directly keeps the
 * vocabulary reviewable and makes a rename/swap a one-file change.
 */
export {
  // topbar
  Bell as NotifyOn,
  BellOff as NotifyOff,
  RefreshCw as Refresh,
  LayoutTemplate as Templates,
  Cpu as SystemSession,
  // a rising line, not bars: at 16px vertical bars read too close to Library's book spines
  ChartLine as Usage,
  Library as SkillLibrary,
  Brain as Memory,
  Compass as ServiceDiscovery,
  Keyboard as Shortcuts,
  LayoutGrid as Expose,
  Settings as SettingsIcon,
  Plus as New,
  Zap as QuickSession,
  // phase 16 layout controls — focus mode is a toggle (.active when on), so no separate
  // grid glyph: one would sit next to Expose (LayoutGrid) and read the same at 16px
  PanelLeft as SidebarToggle,
  RectangleHorizontal as FocusMode,
  BellDot as NeedsYou,

  // session widget header
  FolderTree as EcosystemContext,
  Ticket as LinkedTicket,
  History as ContinuedFrom,
  GitFork as ChildOf,
  GitPullRequest as PullRequest,
  Maximize2 as Maximize,
  Minimize2 as Restore,
  Minus as MinimizeToDock,
  GitBranch as GitPanelIcon,
  FileDiff as ChangesIcon,
  Info as InfoTag,
  // phase 22 Visualize: the header button, plus the dialog's copy-source and pop-out actions
  Workflow as Diagram,
  Clipboard as CopySource,
  ExternalLink as PopOut,
  ZoomIn,
  ZoomOut,
  Scan as FitView,
  Copy as Duplicate,
  Download as DownloadIcon,
  Square as Interrupt,
  Play as Resume,
  // phase 24 demo bar — a recording's transport, not a session action (distinct from Resume)
  Play as DemoPlay,
  Pause as DemoPause,
  RotateCcw as DemoRestart,
  X as Close,
  // distinct from Restore/MinimizeToDock (window-frame controls) — this one's the
  // suggestion card's Compact action
  Shrink as Compact,

  // dialogs & panels
  Sparkles as AiSuggest,
  Folder as LocalSource,
  Cloud as RemoteSource,
  BookOpen as SkillAsset,
  Bot as AgentAsset,
  FilePen as MemoryDocEditable,
  FileText as MemoryDoc,
  BrushCleaning as Cleanup,
  TriangleAlert as Warning,
  ArrowDown as PullChanges,

  // extract a reusable skill from the session — beside the reflect (Memory) button
  BookPlus as ExtractSkill,

  // transcript
  // memory curation marks (phase 21) — Pin: "this block matters"; EyeOff: "keep this out of memory"
  Pin as MarkImportant,
  EyeOff as ExcludeFromMemory,
  // turn one important block into a memory proposal right now (distinct from the whole-session Memory/reflect brain)
  BookmarkPlus as RememberBlock,
  Check as ToolOk,
  X as ToolFailed,
  LoaderCircle as ToolRunning,
} from 'lucide-react';
