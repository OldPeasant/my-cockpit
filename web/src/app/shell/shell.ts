import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { CommandPalette } from './command-palette';
import { ShellAction, shellActionFor } from './hotkeys';
import { NAV_GROUPS } from './nav-sections';
import { CAPTURE_DIALOG } from './shell-tokens';
import { ShortcutsDialog } from './shortcuts-dialog';
import { THEME_MODES, ThemeMode, ThemeService } from './theme.service';

/** Below this width the navigation becomes a drawer (SPEC.md §9.1). */
export const COMPACT_QUERY = '(max-width: 899.98px)';

/** Layout every feature renders into: left navigation, top bar, global shortcuts (SPEC.md §9.1). */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatMenuModule,
    MatSidenavModule,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  host: { '(document:keydown)': 'onKeydown($event)' },
})
export class Shell {
  private readonly dialog = inject(MatDialog);
  private readonly capture = inject(CAPTURE_DIALOG);
  protected readonly theme = inject(ThemeService);

  protected readonly groups = NAV_GROUPS;
  protected readonly themeModes = THEME_MODES;
  protected readonly themeLabels: Record<ThemeMode, string> = {
    light: $localize`:@@theme.light:Light`,
    dark: $localize`:@@theme.dark:Dark`,
    system: $localize`:@@theme.system:System`,
  };

  protected readonly compact = toSignal(
    inject(BreakpointObserver)
      .observe(COMPACT_QUERY)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  private readonly drawer = viewChild.required<MatSidenav>('drawer');

  protected onKeydown(event: KeyboardEvent): void {
    const action = shellActionFor(event);
    if (!action || (this.dialog.openDialogs.length > 0 && action !== 'palette')) {
      return;
    }
    event.preventDefault();
    this.run(action);
  }

  run(action: ShellAction): void {
    switch (action) {
      case 'palette':
        this.openPalette();
        break;
      case 'capture':
        this.capture.open();
        break;
      case 'shortcuts':
        this.dialog.open(ShortcutsDialog, { width: '480px', maxWidth: '95vw' });
        break;
    }
  }

  protected openPalette(): void {
    if (this.dialog.openDialogs.some((ref) => ref.componentInstance instanceof CommandPalette)) {
      return;
    }
    this.dialog.open(CommandPalette, {
      width: '640px',
      maxWidth: '95vw',
      position: { top: '12vh' },
      ariaLabel: $localize`:@@palette.title:Command palette`,
    });
  }

  protected openCapture(): void {
    this.capture.open();
  }

  protected toggleDrawer(): void {
    void this.drawer().toggle();
  }

  protected onNavigate(): void {
    if (this.compact()) {
      void this.drawer().close();
    }
  }
}

export default Shell;
