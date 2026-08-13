import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { FormField } from '@/components/FormField';
import { formatError } from '@/lib/errorMessages';

const schema = z.object({
  displayName: z.string().min(1, 'Display name is required').max(120),
  email: z.string().email('Enter a valid email address'),
  // Backend enforces >=12 chars, 1 letter + 1 digit, mirror it up front so
  // the failure is caught before the network round-trip.
  password: z
    .string()
    .min(12, 'Password must be at least 12 characters')
    .refine((value) => /[a-zA-Z]/.test(value), {
      message: 'Password must include a letter',
    })
    .refine((value) => /\d/.test(value), {
      message: 'Password must include a number',
    }),
});

type FormValues = z.infer<typeof schema>;

export function RegisterPage() {
  const { register: doRegister } = useAuth();
  const navigate = useNavigate();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { displayName: '', email: '', password: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await doRegister(values);
      navigate('/', { replace: true });
    } catch (error) {
      setServerError(formatError(error));
    }
  });

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold text-slate-900 mb-6">Create your account</h1>
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <FormField
          label="Display name"
          autoComplete="name"
          {...register('displayName')}
          error={errors.displayName?.message}
        />
        <FormField
          label="Email"
          type="email"
          autoComplete="email"
          {...register('email')}
          error={errors.email?.message}
        />
        <FormField
          label="Password"
          type="password"
          autoComplete="new-password"
          {...register('password')}
          error={errors.password?.message}
          hint="At least 12 characters with a letter and a number."
        />
        {serverError ? (
          <p role="alert" className="text-sm text-red-600">
            {serverError}
          </p>
        ) : null}
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-md bg-indigo-600 text-white px-4 py-2 font-medium hover:bg-indigo-500 disabled:opacity-60"
        >
          {isSubmitting ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <p className="mt-6 text-sm text-slate-600">
        Already have an account?{' '}
        <Link to="/login" className="text-indigo-600 hover:underline">
          Sign in
        </Link>
      </p>
    </div>
  );
}
