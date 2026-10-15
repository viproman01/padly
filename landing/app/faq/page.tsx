import { FAQ } from '@/components/FAQ';
import { site } from '@/lib/site';

export const metadata = {
  title: `FAQ — ${site.name}`,
};

export default function FaqPage() {
  return (
    <>
      <section className="relative pt-20 sm:pt-28">
        <div className="container-tight max-w-3xl">
          <h1 className="text-4xl font-bold tracking-tight">FAQ</h1>
          <p className="mt-4 text-ink-400">
            Если ответа нет — задай вопрос в{' '}
            <a
              className="text-accent-light hover:underline"
              href={site.links.issues}
              target="_blank"
              rel="noreferrer noopener"
            >
              issues на GitHub
            </a>
            .
          </p>
        </div>
      </section>
      <FAQ />
    </>
  );
}
