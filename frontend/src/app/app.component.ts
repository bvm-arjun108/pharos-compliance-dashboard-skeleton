import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <header class="app-header">
      <div class="header-primary">
        <a class="brand" routerLink="/batches" aria-label="Western Union Compliance Operations home">
          <img
            src="assets/images/Western_Image_Logo.png"
            alt="Western Union"
            width="605"
            height="330"
          />
        </a>
        <div class="product-title">
          <span>Pharos</span>
          <strong>Compliance Operations</strong>
        </div>
        <div class="environment-badge" aria-label="Current environment: Local">
          <span></span>
          Local
        </div>
      </div>

      <nav class="view-nav" aria-label="Dashboard views">
        <div class="view-nav__inner">
          <a routerLink="/batches" routerLinkActive="active">Batch View</a>
          <a routerLink="/report-config" routerLinkActive="active">Report Config</a>
        </div>
      </nav>
    </header>
    <main><router-outlet /></main>
  `
})
export class AppComponent {}
