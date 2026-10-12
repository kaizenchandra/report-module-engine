import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";
import SessionControl from "./session-control";
export const metadata: Metadata = {
  title: "Report Engine",
  description: "Enterprise report operations",
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <a className="skip" href="#main">
          Skip to content
        </a>
        <aside>
          <Link className="brand" href="/">
            ▦ Report Engine
          </Link>
          <p className="eyebrow">WORKSPACE</p>
          <nav aria-label="Main">
            <Link href="/">Overview</Link>
            <Link href="/definitions">Report catalog</Link>
            <Link href="/templates">Templates</Link>
            <Link href="/data-sources">Data sources</Link>
            <Link href="/jobs">Jobs</Link>
            <Link href="/destinations">Destinations</Link>
          </nav>
          <footer>
            Development workspace
            <br />
            All timestamps in UTC
          </footer>
        </aside>
        <div className="workspace">
          <header>
            <span>Reporting / Operations</span>
            <SessionControl />
          </header>
          <main id="main">{children}</main>
        </div>
      </body>
    </html>
  );
}
