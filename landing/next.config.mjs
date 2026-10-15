/** @type {import('next').NextConfig} */
const nextConfig = {
  // Статический экспорт в out/: сайт отдаёт nginx без Node (VPS, landing/deploy/vps/deploy.sh)
  output: 'export',
  reactStrictMode: true,
  poweredByHeader: false,
  images: { unoptimized: true },
};

export default nextConfig;
