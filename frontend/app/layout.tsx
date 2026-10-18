import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import ApolloClientProvider from "./apollo-provider";
import SiteHeader from "./nav/SiteHeader";

const inter = Inter({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: "WhyQL",
  description: "Natural-language analytics platform",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className={inter.className}>
        <SiteHeader />
        <ApolloClientProvider>{children}</ApolloClientProvider>
      </body>
    </html>
  );
}
