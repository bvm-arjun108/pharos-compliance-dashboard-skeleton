import { Component } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

interface PlaceholderViewData {
  eyebrow: string;
  title: string;
  description: string;
  items: string[];
}

@Component({
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="planned-view">
      <div class="planned-view__intro">
        <p class="eyebrow">{{ view.eyebrow }}</p>
        <div class="planned-view__title-row">
          <h1>{{ view.title }}</h1>
          <span>Planned</span>
        </div>
        <p>{{ view.description }}</p>
      </div>

      <div class="planned-view__panel">
        <div class="planned-view__marker">Future</div>
        <div>
          <h2>This view is ready for a later phase.</h2>
          <p>Phase 1 delivery is currently focused on batch operations. This route is reserved so its capabilities can be added without restructuring the dashboard.</p>
        </div>

        <div class="planned-capabilities">
          @for (item of view.items; track item) {
            <div>
              <span aria-hidden="true"></span>
              {{ item }}
            </div>
          }
        </div>

        <a class="back-to-batches" routerLink="/batches">
          Return to Batch View
          <span aria-hidden="true">→</span>
        </a>
      </div>
    </section>
  `
})
export class PlaceholderViewComponent {
  readonly view: PlaceholderViewData;

  constructor(route: ActivatedRoute) {
    this.view = route.snapshot.data as PlaceholderViewData;
  }
}
