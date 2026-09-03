export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body style={{ margin: 0, background: "#07100c", color: "#e4f8ea", fontFamily: "system-ui" }}>{children}</body></html>;
}
