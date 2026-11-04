import * as React from 'react';
import { cn } from '@/lib/utils';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'destructive' | 'outline' | 'secondary' | 'ghost' | 'link' | 'primary' | 'emerald' | 'success' | 'blue' | 'gray';
  size?: 'default' | 'sm' | 'lg' | 'icon';
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'default', size = 'default', children, ...props }, ref) => {
    const variants = {
      default: 'pill-depth-accent font-bold hover:brightness-105 active:scale-95',
      primary: 'pill-depth-accent font-bold hover:brightness-105 active:scale-95',
      emerald: 'pill-depth-emerald font-bold hover:brightness-105 active:scale-95',
      success: 'pill-depth-emerald font-bold hover:brightness-105 active:scale-95',
      blue: 'pill-depth-blue font-bold hover:brightness-105 active:scale-95',
      gray: 'pill-depth-gray font-bold hover:brightness-105 active:scale-95',
      destructive: 'pill-depth-destructive font-bold hover:brightness-105 active:scale-95',
      outline: 'pill-depth-glass text-primary hover:bg-surface-overlay active:scale-95',
      secondary: 'bg-surface-overlay text-primary hover:bg-surface-tertiary border border-subtle active:scale-95',
      ghost: 'hover:bg-surface-overlay hover:text-primary text-secondary active:scale-95',
      link: 'text-accent-primary underline-offset-4 hover:underline p-0 h-auto',
    };

    const sizes = {
      default: 'h-9 px-4 py-2 text-xs',
      sm: 'h-8 px-3 text-xs rounded-lg',
      lg: 'h-10 px-6 text-sm rounded-xl',
      icon: 'h-9 w-9 p-0 flex items-center justify-center rounded-xl',
    };

    return (
      <button
        ref={ref}
        className={cn(
          'inline-flex items-center justify-center gap-2 rounded-xl transition-[color,background-color,border-color,box-shadow,transform,opacity] duration-150 cursor-pointer disabled:opacity-50 disabled:shadow-none! disabled:pointer-events-none select-none active:scale-[0.98] whitespace-nowrap focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary',
          variants[variant] || variants.default,
          sizes[size] || sizes.default,
          className
        )}
        {...props}
      >
        {children}
      </button>
    );
  }
);
Button.displayName = 'Button';
