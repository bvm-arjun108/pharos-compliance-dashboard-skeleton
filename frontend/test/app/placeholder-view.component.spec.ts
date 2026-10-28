import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { PlaceholderViewComponent } from '../../src/app/placeholder-view.component';

describe('PlaceholderViewComponent', () => {
  it('renders the route data', async () => {
    await TestBed.configureTestingModule({
      imports: [PlaceholderViewComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { data: { eyebrow: 'Eyebrow', title: 'Rules', description: 'Desc', items: ['One', 'Two'] } } }
        }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(PlaceholderViewComponent);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Rules');
    expect(text).toContain('One');
    expect(text).toContain('Two');
    expect(fixture.componentInstance.view.eyebrow).toBe('Eyebrow');
  });
});
