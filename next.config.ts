import type { NextConfig } from "next";
const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/backend/:path*",
        destination: `${process.env.CORE_URL ?? "http://localhost:8085"}/backend/:path*`,
      },
    ];
  },
};
export default nextConfig;
