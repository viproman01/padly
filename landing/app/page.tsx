import { Hero } from '@/components/Hero';
import { Features } from '@/components/Features';
import { Screenshots } from '@/components/Screenshots';
import { SetupGuide } from '@/components/SetupGuide';
import { FAQ } from '@/components/FAQ';
import { DownloadButtons } from '@/components/DownloadButtons';

export default function HomePage() {
  return (
    <>
      <Hero />
      <Features />
      <Screenshots />
      <SetupGuide />
      <FAQ />
      <section className="relative py-24">
        <div className="container-tight text-center">
          <h2 className="text-3xl font-bold sm:text-4xl">Готов попробовать?</h2>
          <p className="mt-3 text-ink-400">
            Двух минут хватит, чтобы поставить и забыть про мышку.
          </p>
          <div className="mt-8 flex justify-center">
            <DownloadButtons />
          </div>
        </div>
      </section>
    </>
  );
}
