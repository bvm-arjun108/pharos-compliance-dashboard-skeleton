import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([])]
    }).compileComponents();
  });

  it('creates the application', () => {
    const fixture = TestBed.createComponent(AppComponent);

    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the Compliance Operations product title', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('.product-title')?.textContent).toContain('Compliance Operations');
  });
});
