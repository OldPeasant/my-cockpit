import { Directive } from '@angular/core';

/**
 * Marks a feature's "create linked…" action inside `<app-linked-items>`, e.g.
 * `<button appLinkedCreate (click)="newTaskFromEmail()">New task</button>`. The panel shows the
 * marked elements in its "Create linked" row; after creating the item and its link, the feature
 * calls the panel's `reload()`.
 */
@Directive({ selector: '[appLinkedCreate]' })
export class LinkedCreate {}
