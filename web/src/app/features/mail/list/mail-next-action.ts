import { MailToTaskResultResponse } from '../../../api/generated';

/**
 * The messages of "→ Next action & archive" (SPEC.md §7.3), shared by the read view, the New view
 * and the mail list.
 */

/** A Next action was created from the mail, but the mail could not be archived. */
export function nextActionArchiveFailed(subject: string, result: MailToTaskResultResponse): string {
  const reason =
    result.archiveDetail ||
    result.archiveErrorCode ||
    $localize`:@@mail.nextAction.unexpected:Something went wrong.`;
  return $localize`:@@mail.nextAction.archiveFailed:The next action was created, but “${subject}:subject:” could not be archived: ${reason}:reason:`;
}

/** The Next action could not be created: the mail stays where it is. */
export function nextActionFailed(reason: string): string {
  return $localize`:@@mail.nextAction.failed:The next action could not be created: ${reason}:reason:`;
}

/** Several Next actions were created (link to Next Actions alongside). */
export function nextActionsCreated(count: number): string {
  return $localize`:@@mail.nextAction.created:${count}:count: next actions created`;
}

/**
 * Several mails, not all of them done: one message (a snack bar replaces the one before) saying
 * how many Next actions were created, which mails were not archived (by subject), and how many
 * mails got no Next action and why.
 */
export function nextActionsIncomplete(
  created: number,
  notArchived: readonly string[],
  notCreated: number,
  reason: string,
): string {
  const parts = [$localize`:@@mail.nextAction.createdCount:${created}:count: next actions created.`];
  if (notArchived.length) {
    const subjects = notArchived.map((subject) => `“${subject}”`).join(', ');
    parts.push($localize`:@@mail.nextAction.notArchived:Not archived: ${subjects}:subjects:.`);
  }
  if (notCreated) {
    parts.push(
      $localize`:@@mail.nextAction.notCreated:${notCreated}:count: mails got no next action: ${reason}:reason:`,
    );
  }
  return parts.join(' ');
}

/** The action of the "N next actions created" message. */
export const OPEN_NEXT_ACTIONS = $localize`:@@mail.nextAction.open:Next Actions`;
