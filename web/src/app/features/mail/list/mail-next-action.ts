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

/** The action of the "N next actions created" message. */
export const OPEN_NEXT_ACTIONS = $localize`:@@mail.nextAction.open:Next Actions`;
