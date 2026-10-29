import type { ComponentPropsWithRef } from "react";

export default function Logo(props: ComponentPropsWithRef<'svg'>) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 108 108"
      width="256"
      height="256"
      fill="none"
      stroke="currentColor"
      strokeWidth="8"
      strokeLinejoin="round"
      strokeLinecap="round"
      {...props}
    >
      <path d="M35.3 80 L54 28 L72.7 80" />
      <path d="M44.6 60.2 L63.4 60.2" />
    </svg>
  );
}
