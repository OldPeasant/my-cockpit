import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import {
  ConnectionTestResponse,
  CreateMailAccountRequest,
  MailAccountResponse,
  OAuthConnectionResponse,
  OAuthProviderResponse,
  ProtocolTestResponse,
} from '../../../api/generated';
import { OAuthSlug } from '../calendars/calendar-sources.service';
import { EMAIL_PATTERN, optional, serverForm, serverValue } from './account-form';
import { AccountEditor } from './account-editor';
import { FolderRoles } from './folder-roles';
import { MailAccountsService } from './mail-accounts.service';
import { errorMessage } from './messages';
import { ServerFields } from './server-fields';

/** The kinds of account the add form adds. */
export type AccountType = MailAccountResponse['type'];

/** What is open below an account in the list. */
type Panel = 'edit' | 'folders' | 'remove';

/** A connection test of an account: running, or its result per protocol. */
export interface TestState {
  readonly running: boolean;
  readonly result?: ConnectionTestResponse;
  /** The test request itself failed. */
  readonly error?: string;
  /** How the folder detection after a new account's test went. */
  readonly folders?: string;
}

const TYPE_LABELS: Record<AccountType, string> = {
  IMAP_SMTP: $localize`:@@mailSettings.type.imap:IMAP/SMTP`,
  GOOGLE: $localize`:@@mailSettings.type.google:Google`,
  MICROSOFT: $localize`:@@mailSettings.type.microsoft:Microsoft`,
};

/**
 * Settings → Mail (SPEC.md §7.1): the mail accounts with a connection test, their identity,
 * signature, folder roles and new-mail notification, and adding an IMAP/SMTP, Google or
 * Microsoft account. A new account is tested and its folders detected at once.
 */
@Component({
  selector: 'app-mail-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AccountEditor,
    FolderRoles,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    ReactiveFormsModule,
    ServerFields,
  ],
  templateUrl: './mail-settings.html',
  styleUrls: ['./mail-forms.scss', './mail-settings.scss'],
})
export default class MailSettings implements OnInit {
  private readonly service = inject(MailAccountsService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly accounts = signal<MailAccountResponse[] | null>(null);
  protected readonly providers = signal<OAuthProviderResponse[]>([]);
  protected readonly connections = signal<OAuthConnectionResponse[]>([]);
  protected readonly loadError = signal<string | null>(null);

  /** The account with a panel open, and which. */
  protected readonly open = signal<{ id: string; panel: Panel } | null>(null);
  protected readonly tests = signal<ReadonlyMap<string, TestState>>(new Map());
  /** Ids of the accounts with a change (notification, removal) in flight. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  protected readonly rowError = signal<{ id: string; message: string } | null>(null);
  protected readonly listStatus = signal<string | null>(null);

  protected readonly type = signal<AccountType>('IMAP_SMTP');
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);
  protected readonly addStatus = signal<string | null>(null);

  protected readonly typeLabels = TYPE_LABELS;

  protected readonly imap = this.fb.group({
    displayName: ['', Validators.maxLength(200)],
    emailAddress: ['', [Validators.required, Validators.pattern(EMAIL_PATTERN)]],
    username: ['', Validators.required],
    password: ['', Validators.required],
    imap: serverForm(this.fb, 'imap'),
    smtp: serverForm(this.fb, 'smtp'),
    signatureMd: [''],
    notifyNewMail: [true],
  });
  protected readonly oauth = this.fb.group({
    connectionId: ['', Validators.required],
    emailAddress: ['', Validators.pattern(EMAIL_PATTERN)],
    displayName: ['', Validators.maxLength(200)],
    signatureMd: [''],
    notifyNewMail: [true],
  });

  /** The connections of the provider chosen in the add form. */
  protected readonly typeConnections = computed(() =>
    this.connections().filter((c) => c.provider === this.type()),
  );

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loadError.set(null);
    this.accounts.set(null);
    try {
      const [accounts, providers, connections] = await Promise.all([
        this.service.accounts(),
        this.service.providers().catch(() => []),
        this.service.connections().catch(() => []),
      ]);
      this.providers.set(providers);
      this.connections.set(connections);
      this.accounts.set(accounts);
    } catch (error) {
      this.loadError.set(errorMessage(error));
    }
  }

  protected isOpen(account: MailAccountResponse, panel: Panel): boolean {
    const open = this.open();
    return open?.id === account.id && open.panel === panel;
  }

  protected toggle(account: MailAccountResponse, panel: Panel): void {
    this.rowError.set(null);
    this.listStatus.set(null);
    this.open.set(this.isOpen(account, panel) ? null : { id: account.id, panel });
  }

  protected test(account: MailAccountResponse): TestState | undefined {
    return this.tests().get(account.id);
  }

  protected async runTest(account: MailAccountResponse): Promise<ConnectionTestResponse | null> {
    if (this.test(account)?.running) return null;
    this.setTest(account.id, { running: true });
    try {
      const result = await this.service.test(account.id);
      this.setTest(account.id, { running: false, result });
      return result;
    } catch (error) {
      this.setTest(account.id, { running: false, error: errorMessage(error) });
      return null;
    }
  }

  protected protocolText(test: ProtocolTestResponse): string {
    return test.ok
      ? $localize`:@@mailSettings.test.ok:Connected`
      : (test.message ?? $localize`:@@mailSettings.test.failed:Failed`);
  }

  protected async setNotify(
    account: MailAccountResponse,
    toggle: MatSlideToggleChange,
  ): Promise<void> {
    const notifyNewMail = toggle.checked;
    const ok = await this.change(account, async () => {
      this.replace(await this.service.update(this.current(account), { notifyNewMail }));
    });
    // The binding did not change, so the toggle would keep showing what failed.
    if (!ok) toggle.source.checked = !notifyNewMail;
  }

  protected async remove(account: MailAccountResponse): Promise<void> {
    await this.change(account, async () => {
      await this.service.remove(this.current(account));
      this.accounts.update((all) => all?.filter((a) => a.id !== account.id) ?? null);
      this.open.set(null);
      this.listStatus.set(
        $localize`:@@mailSettings.removed:${account.emailAddress}:email: removed.`,
      );
    });
  }

  protected saved(account: MailAccountResponse): void {
    this.replace(account);
    this.open.set(null);
    // The result was for the settings before the edit; a running test keeps its own answer.
    if (!this.test(account)?.running) {
      this.tests.update((all) => {
        const rest = new Map(all);
        rest.delete(account.id);
        return rest;
      });
    }
  }

  protected selectType(type: AccountType): void {
    this.type.set(type);
    this.addError.set(null);
    this.addStatus.set(null);
    const first = this.connections().find((c) => c.provider === type && c.status === 'CONNECTED');
    this.oauth.reset({ connectionId: first?.id ?? '', notifyNewMail: true });
  }

  protected providerConfigured(type: AccountType): boolean {
    return this.providers().some((p) => p.provider === type && p.configured);
  }

  protected connect(): void {
    this.service.connect(this.type().toLowerCase() as OAuthSlug);
  }

  protected connectionLabel(connection: OAuthConnectionResponse): string {
    const name = connection.email ?? connection.displayName ?? connection.id;
    return connection.status === 'CONNECTED'
      ? name
      : $localize`:@@mailSettings.connection.reconnect:${name}:name: (reconnect it under Integrations)`;
  }

  protected notifyLabel(account: MailAccountResponse): string {
    return $localize`:@@mailSettings.notifyAria:Notify about new mail for ${account.emailAddress}:email:`;
  }

  protected async addImap(): Promise<void> {
    if (!this.ready(this.imap)) return;
    const value = this.imap.getRawValue();
    await this.add(
      {
        type: 'IMAP_SMTP',
        displayName: optional(value.displayName),
        emailAddress: value.emailAddress.trim(),
        username: value.username.trim(),
        password: value.password,
        imap: serverValue(this.imap.controls.imap),
        smtp: serverValue(this.imap.controls.smtp),
        signatureMd: value.signatureMd.trim() ? value.signatureMd : undefined,
        notifyNewMail: value.notifyNewMail,
      },
      () => this.imap.reset(),
    );
  }

  protected async addOAuth(): Promise<void> {
    if (!this.ready(this.oauth)) return;
    const value = this.oauth.getRawValue();
    const connection = this.connections().find((c) => c.id === value.connectionId);
    const email = optional(value.emailAddress) ?? connection?.email;
    if (!email) {
      this.oauth.controls.emailAddress.setErrors({ required: true });
      this.oauth.controls.emailAddress.markAsTouched();
      return;
    }
    await this.add(
      {
        type: this.type(),
        oauthConnectionId: value.connectionId,
        emailAddress: email,
        displayName: optional(value.displayName),
        signatureMd: value.signatureMd.trim() ? value.signatureMd : undefined,
        notifyNewMail: value.notifyNewMail,
      },
      () => this.selectType(this.type()),
    );
  }

  /** Adds an account, then tests it and, when IMAP works, detects its folders. */
  private async add(body: CreateMailAccountRequest, clear: () => void): Promise<void> {
    this.adding.set(true);
    this.addError.set(null);
    this.addStatus.set(null);
    try {
      const account = await this.service.create(body);
      clear();
      this.accounts.update((all) => [...(all ?? []), account]);
      this.addStatus.set(
        $localize`:@@mailSettings.added:${account.emailAddress}:email: added. Testing the connection…`,
      );
      const result = await this.runTest(account);
      if (result?.imap.ok) await this.detectFolders(account);
      this.addStatus.set(
        result?.imap.ok && result.smtp.ok
          ? $localize`:@@mailSettings.addedOk:${account.emailAddress}:email: added and connected.`
          : $localize`:@@mailSettings.addedFailing:${account.emailAddress}:email: added, but the connection does not work yet. Check the result below and fix it with Edit.`,
      );
    } catch (error) {
      this.addError.set(
        $localize`:@@mailSettings.addFailed:The account could not be added: ${errorMessage(error)}:message:`,
      );
    } finally {
      this.adding.set(false);
    }
  }

  private async detectFolders(account: MailAccountResponse): Promise<void> {
    let folders: string;
    try {
      const found = await this.service.syncFolders(account.id);
      folders =
        found.length === 1
          ? $localize`:@@mailSettings.folderFound:1 folder found.`
          : $localize`:@@mailSettings.foldersFound:${found.length}:count: folders found.`;
    } catch (error) {
      folders = $localize`:@@mailSettings.foldersFailed:Folders could not be read: ${errorMessage(error)}:message:`;
    }
    const test = this.tests().get(account.id);
    if (test && !test.running) this.setTest(account.id, { ...test, folders });
  }

  private ready(form: { invalid: boolean; markAllAsTouched(): void }): boolean {
    if (this.adding()) return false;
    if (form.invalid) {
      form.markAllAsTouched();
      return false;
    }
    return true;
  }

  /** Runs a change of an account's row; returns whether it worked. */
  private async change(account: MailAccountResponse, run: () => Promise<void>): Promise<boolean> {
    if (this.busy().has(account.id)) return false;
    this.busy.update((ids) => new Set(ids).add(account.id));
    this.rowError.set(null);
    try {
      await run();
      return true;
    } catch (error) {
      this.rowError.set({ id: account.id, message: errorMessage(error) });
      return false;
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(account.id);
        return rest;
      });
    }
  }

  private current(account: MailAccountResponse): MailAccountResponse {
    return this.accounts()?.find((a) => a.id === account.id) ?? account;
  }

  private replace(account: MailAccountResponse): void {
    this.accounts.update((all) => all?.map((a) => (a.id === account.id ? account : a)) ?? null);
  }

  private setTest(id: string, state: TestState): void {
    this.tests.update((all) => new Map(all).set(id, state));
  }
}
